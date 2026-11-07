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

    if !options.server || options.sock.is_none() {
        if options.help {
            eprintln!("{USAGE}");
        } else {
            eprintln!("ERR only --server mode is supported\n{USAGE}");
        }
        std::process::exit(1);
    }

    let code = bg_helper::server::run_server(&options);
    std::process::exit(code);
}
