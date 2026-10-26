//! Optional, detail-only information. Malformed optional fields must not discard a readable work.
use serde_json::Value;

#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct SourceAuthor {
    pub name: String,
    pub role: Option<String>,
}

#[derive(Clone, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum SourceUpdateKind {
    Metadata,
    Content,
}

#[derive(Clone, Debug, Default, PartialEq, Eq, uniffi::Record)]
pub struct SourceWorkMetadata {
    pub authors: Vec<SourceAuthor>,
    pub status: Option<String>,
    pub updated_at: Option<String>,
    pub update_kind: Option<SourceUpdateKind>,
    pub partial: bool,
}

pub(crate) fn optional_text(value: &Value, limit: usize, partial: &mut bool) -> Option<String> {
    match value {
        Value::Null => None,
        Value::String(text) if text.trim().is_empty() => None,
        Value::String(text) if text.len() <= limit => Some(text.trim().to_owned()),
        _ => { *partial = true; None }
    }
}

pub(crate) fn authors(value: &Value, with_roles: bool, partial: &mut bool) -> Vec<SourceAuthor> {
    if value.is_null() { return Vec::new(); }
    let Some(values) = value.as_array() else { *partial = true; return Vec::new(); };
    if values.len() > 128 { *partial = true; }
    let mut result = Vec::new();
    for value in values.iter().take(128) {
        if !value.is_object() { *partial = true; continue; }
        let Some(name) = optional_text(&value["name"], 512, partial) else { *partial = true; continue; };
        let role = if with_roles { optional_text(&value["role"], 128, partial) } else { None };
        let author = SourceAuthor { name, role };
        if !result.contains(&author) { result.push(author); }
    }
    result
}

pub(crate) fn komga_metadata(authors_value: &Value, status: &Value, modified: &Value) -> SourceWorkMetadata {
    let mut result = SourceWorkMetadata::default();
    result.authors = authors(authors_value, true, &mut result.partial);
    result.status = optional_text(status, 256, &mut result.partial).map(|s| match s.as_str() {
        "ONGOING" => "连载中".into(), "ENDED" => "已完结".into(),
        "ABANDONED" => "已中止".into(), "HIATUS" => "休刊中".into(), _ => s,
    });
    result.updated_at = optional_text(modified, 128, &mut result.partial);
    result.update_kind = result.updated_at.as_ref().map(|_| SourceUpdateKind::Metadata);
    result
}

pub(crate) fn copy_metadata(authors_value: &Value, status: &Value, updated: &Value) -> SourceWorkMetadata {
    let mut result = SourceWorkMetadata::default();
    result.authors = authors(authors_value, false, &mut result.partial);
    if !status.is_null() && !status.is_object() { result.partial = true; }
    result.status = optional_text(&status["display"], 256, &mut result.partial);
    result.updated_at = optional_text(updated, 128, &mut result.partial);
    result.update_kind = result.updated_at.as_ref().map(|_| SourceUpdateKind::Content);
    result
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn roles_and_exact_names_are_preserved_without_merging_different_roles() {
        let result = komga_metadata(&json!([{"name":"Same","role":"writer"},{"name":"Same","role":"penciller"},{"name":"Same","role":"writer"},{"name":"same","role":"other"}]), &json!("ONGOING"), &json!("2026-01-02T03:04:05"));
        assert_eq!(result.authors.len(), 3);
        assert_eq!(result.authors[1].role.as_deref(), Some("penciller"));
        assert_eq!(result.status.as_deref(), Some("连载中"));
        assert_eq!(result.update_kind, Some(SourceUpdateKind::Metadata));
        assert!(!result.partial);
    }
    #[test]
    fn absent_and_empty_metadata_do_not_invent_any_values() {
        assert_eq!(komga_metadata(&Value::Null, &Value::Null, &Value::Null), SourceWorkMetadata::default());
        assert_eq!(copy_metadata(&json!([]), &json!({"value":1}), &json!("  ")), SourceWorkMetadata::default());
    }
    #[test]
    fn copy_uses_display_instead_of_guessing_numeric_status() {
        let result = copy_metadata(&json!([{"name":"Author","path_word":"opaque"}]), &json!({"value":123,"display":"站点自定义状态"}), &json!("2026-03-04 05:06:07"));
        assert_eq!(result.authors[0].name, "Author");
        assert!(result.authors[0].role.is_none());
        assert_eq!(result.status.as_deref(), Some("站点自定义状态"));
        assert_eq!(result.update_kind, Some(SourceUpdateKind::Content));
        assert!(!result.partial);
    }
    #[test]
    fn malformed_fields_preserve_valid_siblings_and_report_partial() {
        let result = komga_metadata(&json!([{"name":"Good","role":false},null,{"name":12},{"name":"Valid","role":"writer"}]), &json!([]), &json!(true));
        assert_eq!(result.authors.len(), 2);
        assert!(result.partial && result.status.is_none() && result.updated_at.is_none());
        let result = copy_metadata(&json!(false), &json!(12), &json!("valid literal date"));
        assert!(result.partial && result.status.is_none());
        assert_eq!(result.updated_at.as_deref(), Some("valid literal date"));
    }
    #[test]
    fn optional_fields_are_bounded_before_ffi() {
        let authors = Value::Array((0..140).map(|i| json!({"name":format!("author-{i}")})).collect());
        let result = copy_metadata(&authors, &json!({"display":"x".repeat(257)}), &json!("x".repeat(129)));
        assert_eq!(result.authors.len(),128); assert!(result.partial);
        assert!(result.status.is_none() && result.updated_at.is_none());
        assert!(copy_metadata(&json!([{"name":"中".repeat(200)}]), &Value::Null, &Value::Null).authors.is_empty());
    }
    #[test]
    fn known_komga_states_and_unknown_status_are_distinct() {
        for (raw,label) in [("ENDED","已完结"),("ABANDONED","已中止"),("HIATUS","休刊中"),("FUTURE","FUTURE")] {
            assert_eq!(komga_metadata(&Value::Null,&json!(raw),&Value::Null).status.as_deref(),Some(label));
        }
    }
}
