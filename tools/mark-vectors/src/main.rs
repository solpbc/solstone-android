// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

//! Prints journal mark vectors from `spl_core::mark::mark_from_jid`.

use serde_json::{json, Value};
use spl_core::mark::mark_from_jid;
use spl_core::relay_window::jid_from_spki;

/// The reference public keys `spl_core::mark` tests against.
const REFERENCE_SPKIS: [&str; 4] = [
    "3059301306072a8648ce3d020106082a8648ce3d03010703420004471c3e758c4904285bba7e53118ed0f524adeb0757d25bd2f8e7b0d76dfa714cdd520f7aca8a8b917acc37f51de8f0c9bbe3ad858382e702dc25a12d09f7a858",
    "3059301306072a8648ce3d020106082a8648ce3d030107034200047cf27b188d034f7e8a52380304b51ac3c08969e277f21b35a60b48fc4766997807775510db8ed040293d9ac69f7430dbba7dade63ce982299e04b79d227873d1",
    "3059301306072a8648ce3d020106082a8648ce3d030107034200048e533b6fa0bf7b4625bb30667c01fb607ef9f8b8a80fef5b300628703187b2a373eb1dbde03318366d069f83a6f5900053c73633cb041b21c55e1a86c1f400b4",
    "3059301306072a8648ce3d020106082a8648ce3d03010703420004ea68d7b6fedf0b71878938d51d71f8729e0acb8c2c6df8b3d79e8a4b90949ee02a2744c972c9fce787014a964a8ea0c84d714feaa4de823fe85a224a4dd048fa",
];
const PLAIN_CASES: u64 = 16;
const HYPHENATED_CASES: usize = 2;

fn main() {
    let mut cases: Vec<Value> = Vec::new();
    for spki_hex in REFERENCE_SPKIS {
        let jid = jid_from_spki(&decode_hex(spki_hex)).expect("reference key derives a jid");
        cases.push(case(&jid, Some(spki_hex)));
    }
    for index in 0..PLAIN_CASES {
        cases.push(case(&synthetic_jid(index), None));
    }
    // The wordlist's four hyphenated words are the ones a stricter validator would refuse.
    let mut found = 0;
    let mut index = PLAIN_CASES;
    while found < HYPHENATED_CASES {
        let jid = synthetic_jid(index);
        let vector = case(&jid, None);
        let hyphenated = vector["mark"]["words"]
            .as_array()
            .expect("words")
            .iter()
            .any(|word| word.as_str().expect("word").contains('-'));
        if hyphenated {
            cases.push(vector);
            found += 1;
        }
        index += 1;
    }
    let out = json!({
        "generator": "tools/mark-vectors, spl_core::mark::mark_from_jid",
        "cases": cases,
    });
    println!("{}", serde_json::to_string_pretty(&out).expect("vectors serialize"));
}

fn case(jid: &str, spki_hex: Option<&str>) -> Value {
    let spec = mark_from_jid(jid).expect("mark derives").to_render_spec();
    let mut vector = json!({
        "jid": jid,
        "mark": serde_json::to_value(spec).expect("spec serializes"),
    });
    if let Some(spki_hex) = spki_hex {
        vector["spki_der_hex"] = json!(spki_hex);
    }
    vector
}

fn synthetic_jid(index: u64) -> String {
    let mixed = index.wrapping_add(1).wrapping_mul(0x9e37_79b9_7f4a_7c15);
    format!(
        "{:08x}-{:04x}-8{:03x}-a{:03x}-{:012x}",
        mixed >> 32,
        (mixed >> 16) & 0xffff,
        mixed & 0xfff,
        (mixed >> 12) & 0xfff,
        index,
    )
}

fn decode_hex(value: &str) -> Vec<u8> {
    (0..value.len())
        .step_by(2)
        .map(|start| u8::from_str_radix(&value[start..start + 2], 16).expect("hex"))
        .collect()
}
