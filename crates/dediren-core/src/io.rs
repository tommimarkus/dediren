use serde::de::DeserializeOwned;
use std::io::Read;

pub fn read_json_input(path: Option<&str>) -> anyhow::Result<String> {
    match path {
        Some(path) => Ok(std::fs::read_to_string(path)?),
        None => {
            let mut text = String::new();
            std::io::stdin().read_to_string(&mut text)?;
            Ok(text)
        }
    }
}

pub fn parse_command_data<T: DeserializeOwned>(text: &str) -> anyhow::Result<T> {
    let value: serde_json::Value = serde_json::from_str(text)?;
    if value
        .get("envelope_schema_version")
        .and_then(serde_json::Value::as_str)
        .is_some()
    {
        let data = value
            .get("data")
            .cloned()
            .ok_or_else(|| anyhow::anyhow!("command envelope does not contain data"))?;
        Ok(serde_json::from_value(data)?)
    } else {
        Ok(serde_json::from_value(value)?)
    }
}
