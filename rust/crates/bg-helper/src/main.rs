//! `bgroot` 可执行入口。
//!
//! 只做三件事：解析参数、按模式分派、把退出码传回去。
//! 真正的逻辑都在库里，便于单测。

use bg_helper::args::{self, USAGE};

const VERSION: &str = env!("CARGO_PKG_VERSION");

fn main() {
    let arguments: Vec<String> = std::env::args().skip(1).collect();
    let options = args::parse(&arguments);

    if options.version {
        println!("bgroot {VERSION} (bettergi-pocket rust helper)");
        return;
    }

    if options.stdio {
        std::process::exit(bg_helper::server::run_stdio(&options));
    }

    if options.server && options.sock.is_some() {
        std::process::exit(bg_helper::server::run_server(&options));
    }

    if options.help {
        eprintln!("{USAGE}");
    } else {
        eprintln!("ERR 需要 --stdio 或 --server --sock PATH\n{USAGE}");
    }
    std::process::exit(1);
}
