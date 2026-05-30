fn main() {
    uniffi::generate_scaffolding("./src/transition.udl").expect("UniFFI scaffolding generation failed");
}
