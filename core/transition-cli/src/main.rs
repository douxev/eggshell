//! transition-cli — local debugging harness.
//!
//! Phase 0: only wraps `hello`. Will grow to seed/dump test databases,
//! exercise crypto round-trips, and replay migrations once those modules exist.

use std::env;

fn main() {
    let name = env::args().nth(1).unwrap_or_default();
    println!("{}", transition_core::hello(name));
}
