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
 *   --tap X Y [--dur MS]               单次点击后退出（uinput 直发，验证用）
 *   --swipe X1 Y1 X2 Y2 [--dur MS]     滑动后退出（uinput 直发，验证用）
 *   --server --sock PATH [--uid UID] [--app-pid PID]
 *          socket 文件 chown 给 --uid，仅该 app 可连
 *          常驻；仅接受 --uid 进程连接，app 消失自动退出
 *
 * server 模式输入注入：统一走系统 input 命令（InputManager 正规管线）。
 * 不创建 uinput 虚拟触摸设备：独立的 DIRECT 触摸设备产生 DOWN 时，
 * 系统可能对窗口进行中的手势发 ACTION_CANCEL，导致用户拖动断触。
 *
 * 协议（行文本，回复 OK / ERR <原因>）：
 *   PING                -> OK pong
 *   PROBE               -> OK <w> <h> input（server 模式注入可用性探测）
 *   TAP x y dur         -> OK（server 模式：经 input tap 注入）
 *   SWIPE x1 y1 x2 y2 dur -> OK（server 模式：经 input swipe 注入）
 *   LONG x y dur        -> OK（server 模式：经 input swipe 同点按下-抬起模拟长按）
 *   BACK                -> OK（经 input keyevent 4 注入）
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

static void emit(int fd, unsigned short type, unsigned short code, int value) {
    struct input_event ev;
    memset(&ev, 0, sizeof(ev));
    ev.type = type;
    ev.code = code;
    ev.value = value;
    if (write(fd, &ev, sizeof(ev)) < 0) {
        /* 设备被移除时忽略；上层会在下次指令前重建 */
    }
}

static void on_signal(int sig) {
    (void)sig;
    running = 0;
}

/* 从 wm size 探测屏幕尺寸；成功返回 1 */
static int detect_screen(void) {
    FILE *p = popen("wm size 2>/dev/null | grep -oE '[0-9]+x[0-9]+' | tail -1", "r");
    if (!p) return 0;
    char buf[64];
    int w = 0, h = 0;
    if (fgets(buf, sizeof(buf), p)) {
        if (sscanf(buf, "%dx%d", &w, &h) == 2 && w > 0 && h > 0) {
            scr_w = w;
            scr_h = h;
            pclose(p);
            return 1;
        }
    }
    pclose(p);
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

static void do_tap(int x, int y, int dur_ms) {
    touch_down(x, y);
    msleep(dur_ms > 0 ? dur_ms : 50);
    touch_up();
}

static void do_swipe(int x1, int y1, int x2, int y2, int dur_ms) {
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
}

/* 包名合法性校验：只允许字母数字、点、下划线，防命令注入 */
static int valid_pkg(const char *s) {
    if (!s || !*s || strlen(s) >= 128) return 0;
    for (const char *p = s; *p; p++) {
        if (!isalnum((unsigned char)*p) && *p != '.' && *p != '_') return 0;
    }
    return 1;
}

/* server 模式经 input 命令注入（InputManager 正规管线，等同真实手指）。
 * 参数由 handle_line 的 %d 解析保证为纯数字，不存在注入面。
 * 返回 0 成功，-1 失败（命令非零退出）。 */
static int input_cmd(const char *args) {
    char cmd[320];
    snprintf(cmd, sizeof(cmd), "input %s 2>/dev/null", args);
    FILE *p = popen(cmd, "r");
    if (!p) return -1;
    char buf[128];
    size_t n = fread(buf, 1, sizeof(buf) - 1, p);
    buf[n] = '\0';
    int rc = pclose(p);
    return rc == 0 ? 0 : -1;
}

/* 解析 dumpsys 里的前台 ActivityRecord，取包名；找不到置空 */
static void query_foreground(char *out, size_t out_sz) {
    out[0] = '\0';
    FILE *p = popen(
        "dumpsys activity activities 2>/dev/null | "
        "grep -m1 -E 'topResumedActivity|mResumedActivity|mFocusedApp' ",
        "r");
    if (!p) return;
    char buf[1024];
    while (fgets(buf, sizeof(buf), p)) {
        char *start = strchr(buf, '{');
        if (!start) continue;
        /* 多用户兜底：优先找 " u<数字> " 用户段（u0 单用户），找不到则回退到
           不区分用户段（工作资料/多开 u10+ 时也能拿到包名） */
        char *mark = strchr(buf, ' ');
        char *pkg_after_user = NULL;
        while (mark) {
            if (mark[1] == 'u' && isdigit((unsigned char)mark[2])) {
                char *sp = &mark[2];
                while (isdigit((unsigned char)*sp)) sp++;
                if (*sp == ' ') { pkg_after_user = sp + 1; break; }
            }
            mark = strchr(mark + 1, ' ');
        }
        const char *pkg_start;
        if (pkg_after_user) {
            pkg_start = pkg_after_user;
        } else {
            /* 无用户段（非常规格式）：回退到"最后一个 / 前的空格"取组件包名，
               避免把 ActivityRecord 的 hash 误当包名 */
            char *fb_slash = strrchr(start + 1, '/');
            pkg_start = start + 1;
            if (fb_slash) {
                char *sp = fb_slash;
                while (sp > start && *sp != ' ') sp--;
                if (*sp == ' ') pkg_start = sp + 1;
            }
        }
        char *slash = strchr(pkg_start, '/');
        char *end = slash ? slash : pkg_start;
        while (*end && *end != ' ' && *end != '}' && *end != '\n') end++;
        size_t n = (size_t)(end - pkg_start);
        if (n > 0 && n < out_sz) {
            memcpy(out, pkg_start, n);
            out[n] = '\0';
            break;
        }
    }
    pclose(p);
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
                 (ufd >= 0) ? "uinput_ok" : "input");
        return 0;
    }
    /* 注入指令：server 模式（ufd<0）统一经 input 命令（InputManager 正规管线，等同真实手指）；
     * 非 server（--probe/--tap/--swipe 验证模式）用 uinput 直发。 */
    if (sscanf(line, "TAP %d %d %d", &x, &y, &dur) == 3) {
        if (ufd >= 0) {
            do_tap(x, y, dur);
            snprintf(resp, resp_sz, "OK tap\n");
        } else {
            char args[96];
            snprintf(args, sizeof(args), "tap %d %d", x, y);
            if (input_cmd(args) == 0) snprintf(resp, resp_sz, "OK tap\n");
            else snprintf(resp, resp_sz, "ERR input\n");
        }
        return 0;
    }
    if (sscanf(line, "LONG %d %d %d", &x, &y, &dur) == 3) {
        if (ufd >= 0) {
            do_tap(x, y, dur);
            snprintf(resp, resp_sz, "OK long\n");
        } else {
            char args[96];
            snprintf(args, sizeof(args), "swipe %d %d %d %d %d", x, y, x, y, dur);
            if (input_cmd(args) == 0) snprintf(resp, resp_sz, "OK long\n");
            else snprintf(resp, resp_sz, "ERR input\n");
        }
        return 0;
    }
    if (sscanf(line, "SWIPE %d %d %d %d %d", &x1, &y1, &x2, &y2, &dur) == 5) {
        if (ufd >= 0) {
            do_swipe(x1, y1, x2, y2, dur);
            snprintf(resp, resp_sz, "OK swipe\n");
        } else {
            char args[112];
            snprintf(args, sizeof(args), "swipe %d %d %d %d %d", x1, y1, x2, y2, dur);
            if (input_cmd(args) == 0) snprintf(resp, resp_sz, "OK swipe\n");
            else snprintf(resp, resp_sz, "ERR input\n");
        }
        return 0;
    }
    if (strncmp(line, "PID ", 4) == 0) {
        char pkg[128];
        if (sscanf(line, "PID %127s", pkg) == 1 && valid_pkg(pkg)) {
            char cmd[256];
            snprintf(cmd, sizeof(cmd), "pidof %s 2>/dev/null", pkg);
            int pid = 0;
            FILE *p = popen(cmd, "r");
            if (p) {
                char buf[64];
                if (fgets(buf, sizeof(buf), p)) pid = atoi(buf);
                pclose(p);
            }
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
            snprintf(cmd, sizeof(cmd), "dumpsys deviceidle whitelist +%s >/dev/null 2>&1", pkg);
            FILE *p1 = popen(cmd, "r");
            if (p1) pclose(p1);
            snprintf(cmd, sizeof(cmd), "am set-standby-bucket %s active >/dev/null 2>&1", pkg);
            FILE *p2 = popen(cmd, "r");
            if (p2) pclose(p2);
            snprintf(resp, resp_sz, "OK keepalive\n");
        } else {
            snprintf(resp, resp_sz, "ERR bad package\n");
        }
        return 0;
    }
    if (strncmp(line, "BACK", 4) == 0) {
        if (input_cmd("keyevent 4") == 0) snprintf(resp, resp_sz, "OK back\n");
        else snprintf(resp, resp_sz, "ERR input\n");
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
        chown(sock_path, allow_uid, allow_uid);
    }
    if (listen(sfd, 4) < 0) {
        fprintf(stderr, "ERR listen: %s\n", strerror(errno));
        close(sfd);
        return 2;
    }
    fprintf(stderr, "OK listening %s (uid=%d)\n", sock_path, allow_uid);
    fflush(stderr);

    while (running) {
        if (app_pid > 0 && kill(app_pid, 0) != 0 && errno == ESRCH) {
            fprintf(stderr, "app process gone, helper exit\n");
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
                fprintf(stderr, "peercred failed: %s (accepting anyway)\n", strerror(errno));
            }
        }
        char line[MAX_LINE];
        size_t used = 0;
        int quit = 0;
        for (;;) {
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
        /* root 版统一走系统 input 注入（InputManager 正规管线，等同真实手指）。
         * 不再创建 uinput 虚拟触摸设备：独立的 DIRECT 触摸设备产生 DOWN 时，
         * 系统可能对窗口进行中的手势发 ACTION_CANCEL，导致用户拖动断触。 */
        ufd = -1;
        fprintf(stderr, "OK server mode (no uinput, input-cmd injection)\n");
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
        do_tap(x, y, dur);
        fprintf(stderr, "OK tap %d %d dur=%d elapsed=%ldms\n", x, y, dur, now_ms() - t0);
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
        do_swipe(x1, y1, x2, y2, dur);
        fprintf(stderr, "OK swipe\n");
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
