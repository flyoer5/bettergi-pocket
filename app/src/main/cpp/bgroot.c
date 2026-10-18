/*
 * bgroot — BetterGI Pocket root 注入 helper（root 版）
 *
 * 职责：
 *   1) 创建 uinput 虚拟多点触摸设备（INPUT_PROP_DIRECT，被系统视为直接触摸屏）
 *   2) 通过 Unix socket 接收指令，注入触摸事件
 *   3) 提供受限的 dumpsys 查询（前台包名）
 *
 * 模式：
 *   --probe [--hold 秒]                创建设备并保持（验证用）
 *   --tap X Y [--dur MS]               单次点击后退出
 *   --swipe X1 Y1 X2 Y2 [--dur MS]     滑动后退出
 *   --server --sock PATH [--uid UID] [--app-pid PID]
 *          socket 文件 chown 给 --uid，仅该 app 可连
 *          常驻；仅接受 --uid 进程连接，app 消失自动退出
 *
 * 协议（行文本，回复 OK / ERR <原因>）：
 *   PING                -> OK pong
 *   PROBE               -> OK <w> <h> uinput
 *   TAP x y dur         -> OK
 *   SWIPE x1 y1 x2 y2 dur -> OK
 *   LONG x y dur        -> OK
 *   FG                  -> OK <前台包名>
 *   PID <pkg>           -> OK <pid|0>
 *   KEEPALIVE <pkg>     -> OK（电池白名单 + active 待机桶）
 *   QUIT                -> OK bye
 *
 * 构建：见同目录 build.sh（Debian/aarch64 静态 PIE，可在 Android 直接执行）
 */
#define _GNU_SOURCE
#include <ctype.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/input.h>
#include <linux/uinput.h>
#include <signal.h>
#include <sys/select.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <sys/un.h>
#include <time.h>
#include <unistd.h>

#define MAX_LINE 256

static int ufd = -1;
static int scr_w = 0;
static int scr_h = 0;
static int next_tid = 1;
static volatile sig_atomic_t running = 1;
static int app_pid = 0;
/* 未显式传 --app-pid 时，用"父进程是否还在"兜底判断 app 存活 */
static pid_t watch_ppid = 0;

static long now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec * 1000L + ts.tv_nsec / 1000000L;
}

static void msleep(long ms) {
    struct timespec ts;
    ts.tv_sec = ms / 1000;
    ts.tv_nsec = (ms % 1000) * 1000000L;
    nanosleep(&ts, NULL);
}

/* 最近一次注入是否有事件写失败（设备被移除 / O_NONBLOCK 缓冲满） */
static int emit_failed = 0;

static void emit(int fd, unsigned short type, unsigned short code, int value) {
    struct input_event ev;
    memset(&ev, 0, sizeof(ev));
    ev.type = type;
    ev.code = code;
    ev.value = value;
    if (write(fd, &ev, sizeof(ev)) < 0) {
        /* 不在这里重试：记录失败，由 do_tap/do_swipe 汇总后如实回错，
         * 避免"客户端收到 OK、实际一个事件都没注入"的假成功 */
        emit_failed = 1;
    }
}

static void on_signal(int sig) {
    (void)sig;
    running = 0;
}

/**
 * 执行 shell 命令并抓取标准输出（最多 out_sz-1 字节，保证 NUL 结尾）。
 *
 * 为什么不用 popen：glibc 的 popen 硬编码 "/bin/sh"，而 Android 只有 /system/bin/sh，
 * 没有 /bin 目录 → popen 子进程 exec 失败（退出码 127），调用方读到的永远是空输出。
 * 这会让 FG / PID / KEEPALIVE / BACK / wm size 这些功能全部"静默失效"（不报错、返回空）。
 *
 * @return 子进程退出码；fork/pipe 失败返回 -1
 */
static int shell_capture(const char *cmd, char *out, size_t out_sz) {
    /* out 传 NULL / 0 表示"只要退出码，不要输出"：内部用临时缓冲接收 */
    char local[256];
    if (out == NULL || out_sz == 0) {
        out = local;
        out_sz = sizeof(local);
    }
    out[0] = '\0';

    int fds[2];
    if (pipe(fds) != 0) return -1;

    pid_t pid = fork();
    if (pid < 0) {
        close(fds[0]);
        close(fds[1]);
        return -1;
    }
    if (pid == 0) {
        /* 子进程：stdout -> pipe，stderr -> /dev/null */
        close(fds[0]);
        if (dup2(fds[1], STDOUT_FILENO) < 0) _exit(127);
        close(fds[1]);
        int devnull = open("/dev/null", O_WRONLY);
        if (devnull >= 0) {
            dup2(devnull, STDERR_FILENO);
            close(devnull);
        }
        /* 依次尝试 Android 上真实存在的 shell（含 Magisk 自带 busybox） */
        static const char *shells[] = {
            "/system/bin/sh",
            "/bin/sh",
            "/data/adb/magisk/busybox",
            NULL,
        };
        for (int i = 0; shells[i] != NULL; i++) {
            execl(shells[i], "sh", "-c", cmd, (char *)NULL);
        }
        _exit(127);
    }

    close(fds[1]);
    size_t used = 0;
    for (;;) {
        ssize_t n = read(fds[0], out + used, out_sz - 1 - used);
        if (n <= 0) break;
        used += (size_t)n;
        if (used >= out_sz - 1) break;
    }
    out[used] = '\0';
    /* 先关读端再 waitpid：输出超过缓冲时子进程写管道会拿到 EPIPE 立即退出，不会死等 */
    close(fds[0]);

    int status = 0;
    if (waitpid(pid, &status, 0) < 0) return -1;
    return WIFEXITED(status) ? WEXITSTATUS(status) : -1;
}

/** 执行命令并丢弃输出 */
static void shell_run(const char *cmd) {
    char sink[128];
    shell_capture(cmd, sink, sizeof(sink));
}

/* 从 wm size 探测屏幕尺寸；成功返回 1 */
static int detect_screen(void) {
    char buf[128];
    if (shell_capture("wm size 2>/dev/null | grep -oE '[0-9]+x[0-9]+' | tail -1", buf, sizeof(buf)) != 0) {
        return 0;
    }
    int w = 0, h = 0;
    if (sscanf(buf, "%dx%d", &w, &h) == 2 && w > 0 && h > 0) {
        scr_w = w;
        scr_h = h;
        return 1;
    }
    return 0;
}

/* 创建 uinput 虚拟触摸设备；返回 fd，失败返回 -1 */
static int create_uinput(void) {
    int fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK);
    if (fd < 0) {
        fprintf(stderr, "ERR open /dev/uinput: %s\n", strerror(errno));
        return -1;
    }

    if (ioctl(fd, UI_SET_EVBIT, EV_SYN) < 0 ||
        ioctl(fd, UI_SET_EVBIT, EV_KEY) < 0 ||
        ioctl(fd, UI_SET_EVBIT, EV_ABS) < 0 ||
        ioctl(fd, UI_SET_KEYBIT, BTN_TOUCH) < 0 ||
        ioctl(fd, UI_SET_ABSBIT, ABS_MT_SLOT) < 0 ||
        ioctl(fd, UI_SET_ABSBIT, ABS_MT_TRACKING_ID) < 0 ||
        ioctl(fd, UI_SET_ABSBIT, ABS_MT_POSITION_X) < 0 ||
        ioctl(fd, UI_SET_ABSBIT, ABS_MT_POSITION_Y) < 0 ||
        ioctl(fd, UI_SET_ABSBIT, ABS_MT_PRESSURE) < 0 ||
        ioctl(fd, UI_SET_ABSBIT, ABS_MT_TOUCH_MAJOR) < 0 ||
        ioctl(fd, UI_SET_PROPBIT, INPUT_PROP_DIRECT) < 0) {
        fprintf(stderr, "ERR UI_SET_*BIT: %s\n", strerror(errno));
        close(fd);
        return -1;
    }

    struct uinput_abs_setup abs;
    struct {
        int code;
        int max;
    } axes[] = {
        { ABS_MT_SLOT, 9 },
        { ABS_MT_TRACKING_ID, 65535 },
        { ABS_MT_POSITION_X, scr_w - 1 },
        { ABS_MT_POSITION_Y, scr_h - 1 },
        { ABS_MT_PRESSURE, 255 },
        { ABS_MT_TOUCH_MAJOR, 255 },
    };
    for (size_t i = 0; i < sizeof(axes) / sizeof(axes[0]); i++) {
        memset(&abs, 0, sizeof(abs));
        abs.code = axes[i].code;
        abs.absinfo.minimum = 0;
        abs.absinfo.maximum = axes[i].max;
        if (ioctl(fd, UI_ABS_SETUP, &abs) < 0) {
            fprintf(stderr, "ERR UI_ABS_SETUP %d: %s\n", axes[i].code, strerror(errno));
            close(fd);
            return -1;
        }
    }

    struct uinput_setup us;
    memset(&us, 0, sizeof(us));
    us.id.bustype = BUS_VIRTUAL;
    us.id.vendor = 0x6267; /* 'bg' */
    us.id.product = 0x0001;
    us.id.version = 1;
    snprintf(us.name, UINPUT_MAX_NAME_SIZE, "BetterGI Virtual Touch");
    if (ioctl(fd, UI_DEV_SETUP, &us) < 0) {
        fprintf(stderr, "ERR UI_DEV_SETUP: %s\n", strerror(errno));
        close(fd);
        return -1;
    }
    if (ioctl(fd, UI_DEV_CREATE) < 0) {
        fprintf(stderr, "ERR UI_DEV_CREATE: %s\n", strerror(errno));
        close(fd);
        return -1;
    }
    msleep(120); /* 等内核把设备注册进输入子系统 */
    return fd;
}

static void destroy_uinput(void) {
    if (ufd >= 0) {
        ioctl(ufd, UI_DEV_DESTROY);
        close(ufd);
        ufd = -1;
    }
}

static void touch_down(int x, int y) {
    emit(ufd, EV_ABS, ABS_MT_SLOT, 0);
    emit(ufd, EV_ABS, ABS_MT_TRACKING_ID, next_tid++ & 0x7fff);
    emit(ufd, EV_ABS, ABS_MT_POSITION_X, x);
    emit(ufd, EV_ABS, ABS_MT_POSITION_Y, y);
    emit(ufd, EV_ABS, ABS_MT_PRESSURE, 128);
    emit(ufd, EV_ABS, ABS_MT_TOUCH_MAJOR, 8);
    emit(ufd, EV_KEY, BTN_TOUCH, 1);
    emit(ufd, EV_SYN, SYN_REPORT, 0);
}

static void touch_move(int x, int y) {
    emit(ufd, EV_ABS, ABS_MT_POSITION_X, x);
    emit(ufd, EV_ABS, ABS_MT_POSITION_Y, y);
    emit(ufd, EV_SYN, SYN_REPORT, 0);
}

static void touch_up(void) {
    emit(ufd, EV_ABS, ABS_MT_SLOT, 0);
    emit(ufd, EV_ABS, ABS_MT_TRACKING_ID, -1);
    emit(ufd, EV_KEY, BTN_TOUCH, 0);
    emit(ufd, EV_SYN, SYN_REPORT, 0);
}

static int do_tap(int x, int y, int dur_ms) {
    emit_failed = 0;
    touch_down(x, y);
    msleep(dur_ms > 0 ? dur_ms : 50);
    touch_up();
    return emit_failed ? -1 : 0;
}

static int do_swipe(int x1, int y1, int x2, int y2, int dur_ms) {
    emit_failed = 0;
    int steps = dur_ms / 10;
    if (steps < 2) steps = 2;
    touch_down(x1, y1);
    for (int i = 1; i <= steps; i++) {
        int x = x1 + (x2 - x1) * i / steps;
        int y = y1 + (y2 - y1) * i / steps;
        touch_move(x, y);
        msleep(10);
    }
    touch_up();
    return emit_failed ? -1 : 0;
}

/* 包名合法性校验：只允许字母数字、点、下划线，防命令注入 */
static int valid_pkg(const char *s) {
    if (!s || !*s || strlen(s) >= 128) return 0;
    for (const char *p = s; *p; p++) {
        if (!isalnum((unsigned char)*p) && *p != '.' && *p != '_') return 0;
    }
    return 1;
}

/* 解析 dumpsys 里的前台 ActivityRecord，取包名；找不到置空 */
/* 从一行 dumpsys 输出里提取包名。
 * 兼容: "ActivityRecord{abc u0 com.x/com.y.Act t1}"、"u10 com.x/com.y"、
 *       "mCurrentFocus=Window{abc u0 com.x/com.y.Act}"、"com.x/com.y.Act" */
static int extract_pkg_from_line(const char *line, char *out, size_t out_sz) {
    const char *slash = strchr(line, '/');
    if (!slash) return 0;
    const char *start = slash;
    while (start > line) {
        char c = *(start - 1);
        if (c == ' ' || c == '\t' || c == '{' || c == '}') break;
        start--;
    }
    size_t n = (size_t)(slash - start);
    if (n == 0) return 0;
    /* 去掉粘连的 user 前缀（u0xxx / u10xxx）；正常情况有空格分隔，走不到这里 */
    if (start[0] == 'u') {
        size_t k = 1;
        while (k < n && start[k] >= '0' && start[k] <= '9') k++;
        if (k > 1 && k < n) {
            start += k;
            n -= k;
        }
    }
    if (n == 0 || n >= out_sz) return 0;
    memcpy(out, start, n);
    out[n] = '\0';
    return 1;
}

/* 逐行解析 dumpsys 输出，返回首个可识别的前台包名 */
static int parse_foreground(const char *dump, char *out, size_t out_sz) {
    const char *p = dump;
    while (*p != '\0') {
        const char *eol = strchr(p, '\n');
        size_t len = eol ? (size_t)(eol - p) : strlen(p);
        char line[512];
        if (len >= sizeof(line)) len = sizeof(line) - 1;
        memcpy(line, p, len);
        line[len] = '\0';
        if (strchr(line, '/') != NULL && extract_pkg_from_line(line, out, out_sz)) return 1;
        if (eol == NULL) break;
        p = eol + 1;
    }
    return 0;
}

/* 查询前台包名：多来源依次尝试（窗口焦点最快也最稳，Activity 栈兜底）。
 * 旧实现硬要求 " u0 "，工作资料(u10)/部分 ROM 直接解析失败 → 上层把 null
 * 当"不在前台"从而误拉起游戏，这里改为任意 user id 均可。 */
static void query_foreground(char *out, size_t out_sz) {
    out[0] = '\0';
    static const char *cmds[] = {
        "dumpsys window 2>/dev/null | grep -m1 -E 'mCurrentFocus|mFocusedApp'",
        "dumpsys activity activities 2>/dev/null | grep -m1 -E 'topResumedActivity|mResumedActivity'",
        "dumpsys activity 2>/dev/null | grep -m1 'mFocusedActivity'",
        NULL,
    };
    char dump[1024];
    for (int i = 0; cmds[i] != NULL; i++) {
        dump[0] = '\0';
        shell_capture(cmds[i], dump, sizeof(dump));
        if (dump[0] == '\0') continue;
        if (parse_foreground(dump, out, out_sz)) return;
    }
}

/* 处理一行指令；返回 1 表示 QUIT */
static int handle_line(const char *line, char *resp, size_t resp_sz) {
    int x, y, dur, x1, y1, x2, y2;
    if (strncmp(line, "PING", 4) == 0) {
        snprintf(resp, resp_sz, "OK pong\n");
        return 0;
    }
    if (strncmp(line, "PROBE", 5) == 0) {
        snprintf(resp, resp_sz, "OK %d %d %s\n", scr_w, scr_h,
                 (ufd >= 0) ? "uinput_ok" : "uinput_fail");
        return 0;
    }
    if (ufd < 0 && (strncmp(line, "TAP", 3) == 0 ||
                     strncmp(line, "LONG", 4) == 0 ||
                     strncmp(line, "SWIPE", 5) == 0)) {
        snprintf(resp, resp_sz, "ERR uinput\n");
        return 0;
    }
    if (sscanf(line, "TAP %d %d %d", &x, &y, &dur) == 3) {
        snprintf(resp, resp_sz, do_tap(x, y, dur) == 0 ? "OK tap\n" : "ERR tap\n");
        return 0;
    }
    if (sscanf(line, "LONG %d %d %d", &x, &y, &dur) == 3) {
        snprintf(resp, resp_sz, do_tap(x, y, dur) == 0 ? "OK long\n" : "ERR long\n");
        return 0;
    }
    if (sscanf(line, "SWIPE %d %d %d %d %d", &x1, &y1, &x2, &y2, &dur) == 5) {
        snprintf(resp, resp_sz, do_swipe(x1, y1, x2, y2, dur) == 0 ? "OK swipe\n" : "ERR swipe\n");
        return 0;
    }
    if (strncmp(line, "PID ", 4) == 0) {
        char pkg[128];
        if (sscanf(line, "PID %127s", pkg) == 1 && valid_pkg(pkg)) {
            char cmd[256];
            snprintf(cmd, sizeof(cmd), "pidof %s 2>/dev/null", pkg);
            char buf[64] = "";
            shell_capture(cmd, buf, sizeof(buf));
            int pid = buf[0] ? atoi(buf) : 0;
            snprintf(resp, resp_sz, "OK %d\n", pid);
        } else {
            snprintf(resp, resp_sz, "ERR bad package\n");
        }
        return 0;
    }
    if (strncmp(line, "KEEPALIVE ", 10) == 0) {
        char pkg[128];
        if (sscanf(line, "KEEPALIVE %127s", pkg) == 1 && valid_pkg(pkg)) {
            char cmd[320];
            int ok = 0;
            snprintf(cmd, sizeof(cmd), "dumpsys deviceidle whitelist +%s", pkg);
            if (shell_capture(cmd, NULL, 0) != 0) ok = 1;   /* 只看退出码，输出丢弃 */
            snprintf(cmd, sizeof(cmd), "am set-standby-bucket %s active", pkg);
            if (shell_capture(cmd, NULL, 0) != 0) ok = 1;
            if (ok == 0) {
                snprintf(resp, resp_sz, "OK keepalive\n");
            } else {
                snprintf(resp, resp_sz, "ERR keepalive\n");
            }
        } else {
            snprintf(resp, resp_sz, "ERR bad package\n");
        }
        return 0;
    }
    if (strncmp(line, "BACK", 4) == 0) {
        shell_run("input keyevent 4 2>/dev/null");
        snprintf(resp, resp_sz, "OK back\n");
        return 0;
    }
    if (strncmp(line, "FG", 2) == 0) {
        char fg[256];
        query_foreground(fg, sizeof(fg));
        snprintf(resp, resp_sz, "OK %s\n", fg[0] ? fg : "unknown");
        return 0;
    }
    if (strncmp(line, "QUIT", 4) == 0) {
        snprintf(resp, resp_sz, "OK bye\n");
        return 1;
    }
    snprintf(resp, resp_sz, "ERR unknown command\n");
    return 0;
}

static int run_server(const char *sock_path, int allow_uid) {
    struct sockaddr_un addr;
    if (strlen(sock_path) >= sizeof(addr.sun_path)) {
        fprintf(stderr, "ERR socket path too long\n");
        return 2;
    }
    int sfd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (sfd < 0) {
        fprintf(stderr, "ERR socket: %s\n", strerror(errno));
        return 2;
    }
    unlink(sock_path);
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    strncpy(addr.sun_path, sock_path, sizeof(addr.sun_path) - 1);
    if (bind(sfd, (struct sockaddr *)&addr, sizeof(addr)) < 0) {
        fprintf(stderr, "ERR bind: %s\n", strerror(errno));
        close(sfd);
        return 2;
    }
    chmod(sock_path, 0600);
    /* 把 socket 文件所有权让渡给本 app：Unix socket connect 需要文件写权限，
       否则 app（非 root）对 root:0600 的文件无法连接（EACCES） */
    if (allow_uid > 0) {
        /* chown 失败 = app 连不上（socket 仍是 root:0600）→ 必须显式失败，别留个静默不可用的服务 */
        if (chown(sock_path, allow_uid, allow_uid) != 0) {
            fprintf(stderr, "ERR chown %s -> uid %d: %s\n", sock_path, allow_uid, strerror(errno));
            close(sfd);
            unlink(sock_path);
            return 2;
        }
    }
    if (listen(sfd, 4) < 0) {
        fprintf(stderr, "ERR listen: %s\n", strerror(errno));
        close(sfd);
        return 2;
    }
    fprintf(stderr, "OK listening %s (uid=%d)\n", sock_path, allow_uid);
    fflush(stderr);

    while (running) {
        if (app_pid > 0) {
            if (kill(app_pid, 0) != 0 && errno == ESRCH) {
                fprintf(stderr, "app process gone, helper exit\n");
                break;
            }
        } else if (watch_ppid > 1 && getppid() != watch_ppid) {
            /* app 被 kill 后本进程被 init 收养，getppid() 会变 → 立刻退出，
             * 避免 --app-pid 缺失时留下永久驻留的 root 进程 */
            fprintf(stderr, "parent gone (ppid %d -> %d), helper exit\n",
                    (int)watch_ppid, (int)getppid());
            break;
        }
        /* 用 select 1s 超时轮询，避免 accept 阻塞时无法检查 app 存活 */
        fd_set rfds;
        FD_ZERO(&rfds);
        FD_SET(sfd, &rfds);
        struct timeval tv;
        tv.tv_sec = 1;
        tv.tv_usec = 0;
        int sr = select(sfd + 1, &rfds, NULL, NULL, &tv);
        if (sr < 0) {
            if (errno == EINTR) continue;
            break;
        }
        if (sr == 0) continue; /* 超时：回到循环头检查 app 存活 */
        int cfd = accept(sfd, NULL, NULL);
        if (cfd < 0) {
            if (errno == EINTR) continue;
            break;
        }
        if (allow_uid > 0) {
            struct ucred cred;
            socklen_t len = sizeof(cred);
            if (getsockopt(cfd, SOL_SOCKET, SO_PEERCRED, &cred, &len) == 0) {
                fprintf(stderr, "peer uid=%d\n", (int)cred.uid);
                if ((int)cred.uid != allow_uid) {
                    fprintf(stderr, "reject uid %d (expect %d)\n", (int)cred.uid, allow_uid);
                    close(cfd);
                    continue;
                }
            } else {
                /* 取不到对端凭据视为不可信：socket 文件虽已 0600 + chown 给本 app,
                 * 但校验失败时仍按拒绝处理, 不放行任何未验证连接 */
                fprintf(stderr, "reject connection: peercred failed: %s\n", strerror(errno));
                close(cfd);
                continue;
            }
        }
        char line[MAX_LINE];
        size_t used = 0;
        int quit = 0;
        for (;;) {
            if (used >= sizeof(line) - 1) {
                /* 单行超长：协议里最长指令不足 64B，达到缓冲区上限说明流已错乱。
                 * 以前这里 read(..., 0) 返回 0 被当成 EOF 静默断开；现在明确回错误再断开。 */
                static const char too_long[] = "ERR line too long\n";
                (void)!write(cfd, too_long, sizeof(too_long) - 1);
                fprintf(stderr, "line too long, closing connection\n");
                break;
            }
            ssize_t n = read(cfd, line + used, sizeof(line) - used - 1);
            if (n <= 0) break;
            used += (size_t)n;
            line[used] = '\0';
            char *nl;
            while ((nl = strchr(line, '\n')) != NULL) {
                *nl = '\0';
                fprintf(stderr, "cmd: %s\n", line);
                fflush(stderr);
                char resp[512];
                if (handle_line(line, resp, sizeof(resp)) == 1) quit = 1;
                if (write(cfd, resp, strlen(resp)) < 0) break;
                size_t rest = used - (size_t)(nl + 1 - line);
                memmove(line, nl + 1, rest);
                used = rest;
                line[used] = '\0';
            }
            if (quit) break;
        }
        close(cfd);
        if (quit) break;
    }
    close(sfd);
    unlink(sock_path);
    if (ufd >= 0) destroy_uinput();
    return 0;
}

static int arg_value(int argc, char **argv, const char *name, int fallback) {
    for (int i = 1; i + 1 < argc; i++) {
        if (strcmp(argv[i], name) == 0) return atoi(argv[i + 1]);
    }
    return fallback;
}

static const char *arg_str(int argc, char **argv, const char *name) {
    for (int i = 1; i + 1 < argc; i++) {
        if (strcmp(argv[i], name) == 0) return argv[i + 1];
    }
    return NULL;
}

static int has_flag(int argc, char **argv, const char *name) {
    for (int i = 1; i < argc; i++) {
        if (strcmp(argv[i], name) == 0) return 1;
    }
    return 0;
}

int main(int argc, char **argv) {
    signal(SIGINT, on_signal);
    signal(SIGTERM, on_signal);
    signal(SIGPIPE, SIG_IGN);

    if (has_flag(argc, argv, "--version")) {
        printf("bgroot 0.1.0 (bettergi-pocket root backend)\n");
        return 0;
    }

    /* 屏幕尺寸：优先命令行参数，否则 wm size 探测，最后兜底 */
    scr_w = arg_value(argc, argv, "--w", 0);
    scr_h = arg_value(argc, argv, "--h", 0);
    app_pid = arg_value(argc, argv, "--app-pid", 0);
    watch_ppid = getppid();
    if (scr_w <= 0 || scr_h <= 0) {
        if (!detect_screen()) {
            scr_w = 1080;
            scr_h = 2400;
        }
    }
    if (scr_w <= 0 || scr_h <= 0) {
        fprintf(stderr, "ERR invalid screen size\n");
        return 3;
    }

    if (has_flag(argc, argv, "--server")) {
        /* 实测结论: uinput 虚拟设备注入的事件原神(米哈游引擎)不响应
         * (点击光圈出现但游戏无反应), 只有 input 命令(InputManager 正规管线,
         * 复用真实触摸屏 deviceId)游戏才认可。server 模式不创建 uinput,
         * 注入统一走 input 命令。 */
        ufd = -1;
        fprintf(stderr, "OK server mode (input-cmd injection)\n");
        fflush(stderr);
    } else {
        ufd = create_uinput();
        if (ufd < 0) {
            fprintf(stderr, "WARN uinput unavailable: %s\n", strerror(errno));
            fflush(stderr);
            return 3;
        }
        fprintf(stderr, "OK uinput created BetterGI Virtual Touch (%dx%d)\n", scr_w, scr_h);
        fflush(stderr);
    }

    if (has_flag(argc, argv, "--probe")) {
        int hold = arg_value(argc, argv, "--hold", 8);
        msleep(hold * 1000);
        destroy_uinput();
        fprintf(stderr, "OK probe done\n");
        return 0;
    }

    if (has_flag(argc, argv, "--tap")) {
        if (argc < 4) {
            fprintf(stderr, "usage: bgroot --tap X Y [--dur ms]\n");
            return 1;
        }
        int x = atoi(argv[2]);
        int y = atoi(argv[3]);
        int dur = arg_value(argc, argv, "--dur", 50);
        long t0 = now_ms();
        int tap_rc = do_tap(x, y, dur);
        fprintf(stderr, "%s tap %d %d dur=%d elapsed=%ldms\n", tap_rc == 0 ? "OK" : "ERR", x, y, dur, now_ms() - t0);
        msleep(150);
        destroy_uinput();
        return 0;
    }

    if (has_flag(argc, argv, "--swipe")) {
        if (argc < 6) {
            fprintf(stderr, "usage: bgroot --swipe X1 Y1 X2 Y2 [--dur ms]\n");
            return 1;
        }
        int x1 = atoi(argv[2]), y1 = atoi(argv[3]);
        int x2 = atoi(argv[4]), y2 = atoi(argv[5]);
        int dur = arg_value(argc, argv, "--dur", 300);
        int sw_rc = do_swipe(x1, y1, x2, y2, dur);
        fprintf(stderr, sw_rc == 0 ? "OK swipe\n" : "ERR swipe\n");
        msleep(150);
        destroy_uinput();
        return 0;
    }

    const char *sock = arg_str(argc, argv, "--sock");
    if (sock) {
        int uid = arg_value(argc, argv, "--uid", 0);
        int rc = run_server(sock, uid);
        destroy_uinput();
        return rc;
    }

    fprintf(stderr,
            "usage: bgroot --probe [--hold s] | --tap X Y [--dur ms] | "
            "--swipe X1 Y1 X2 Y2 [--dur ms] | --server --sock PATH [--uid UID]\n");
    destroy_uinput();
    return 1;
}
