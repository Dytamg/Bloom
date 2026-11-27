use chrono::{Duration, NaiveDate};
use serde::{Deserialize, Serialize};
use std::sync::Mutex;
use zeroize::Zeroizing;
use base64::{Engine as _, engine::general_purpose};
use crate::prediction_engine;

fn default_true() -> bool {
    true
}

#[derive(Serialize, Deserialize, Default, Clone, Debug, PartialEq)]
pub struct PartnerPermissions {
    #[serde(rename = "share_cycle_phase", alias = "shareCyclePhase", default)]
    pub share_cycle_phase: bool,
    #[serde(rename = "share_flow_intensity", alias = "shareFlowIntensity", default)]
    pub share_flow_intensity: bool,
    #[serde(rename = "share_symptoms_moods", alias = "shareSymptomsMoods", default)]
    pub share_symptoms_moods: bool,
    #[serde(rename = "share_birth_control", alias = "shareBirthControl", default)]
    pub share_birth_control: bool,
    #[serde(rename = "share_pregnancy_tests", alias = "sharePregnancyTests", default)]
    pub share_pregnancy_tests: bool,
    #[serde(rename = "share_sexual_activity", alias = "shareSexualActivity", default)]
    pub share_sexual_activity: bool,
}

impl PartnerPermissions {
    pub fn to_bitmask(&self) -> i32 {
        let mut mask = 0i32;
        if self.share_cycle_phase {
            mask |= 0x01;
        }
        if self.share_flow_intensity {
            mask |= 0x02;
        }
        if self.share_symptoms_moods {
            mask |= 0x04;
        }
        if self.share_sexual_activity {
            mask |= 0x08;
        }
        if self.share_birth_control {
            mask |= 0x10;
        }
        if self.share_pregnancy_tests {
            mask |= 0x20;
        }
        mask
    }

    pub fn from_bitmask(mask: i32) -> Self {
        Self {
            share_cycle_phase: (mask & 0x01) != 0,
            share_flow_intensity: (mask & 0x02) != 0,
            share_symptoms_moods: (mask & 0x04) != 0,
            share_sexual_activity: (mask & 0x08) != 0,
            share_birth_control: (mask & 0x10) != 0,
            share_pregnancy_tests: (mask & 0x20) != 0,
        }
    }
}

pub fn deserialize_permissions<'de, D>(deserializer: D) -> Result<Option<PartnerPermissions>, D::Error>
where
    D: serde::Deserializer<'de>,
{
    let value = serde_json::Value::deserialize(deserializer)?;
    match value {
        serde_json::Value::Null => Ok(None),
        serde_json::Value::Number(num) => {
            Ok(Some(PartnerPermissions::from_bitmask(num.as_i64().unwrap_or(0) as i32)))
        }
        serde_json::Value::Object(_) => {
            let p: PartnerPermissions = serde_json::from_value(value).unwrap_or_default();
            Ok(Some(p))
        }
        _ => Ok(None),
    }
}

lazy_static::lazy_static! {
    pub static ref PARTNER_PERMISSIONS: Mutex<PartnerPermissions> = Mutex::new(PartnerPermissions::default());
}

pub fn save_partner_permissions_internal(perms: PartnerPermissions) {
    let mut guard = match PARTNER_PERMISSIONS.lock() {
        Ok(g) => g,
        Err(e) => e.into_inner(),
    };
    *guard = perms;
}

pub fn get_partner_permissions_internal() -> PartnerPermissions {
    let guard = match PARTNER_PERMISSIONS.lock() {
        Ok(g) => g,
        Err(e) => e.into_inner(),
    };
    guard.clone()
}

#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
pub struct SyncEntry {
    pub date: String,
    pub intensity: i32,
    pub notes: Option<String>,
    #[serde(rename = "sexualRelations", alias = "sexual_relations", default)]
    pub sexual_relations: Option<String>,
    #[serde(rename = "painLevel", alias = "pain_level", default)]
    pub pain_level: Option<i32>,
    #[serde(default)]
    pub symptoms: Option<String>,
}

#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
pub struct SyncBirthControl {
    #[serde(rename = "type")]
    pub r#type: String,
    #[serde(rename = "isActive", alias = "is_active", default = "default_true")]
    pub is_active: bool,
    #[serde(rename = "reminderTime", alias = "reminder_time", default, skip_serializing_if = "Option::is_none")]
    pub reminder_time: Option<String>,
}

#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
pub struct SyncPregnancyTest {
    pub date: String,
    pub result: String,
}

#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
pub struct SyncReminder {
    #[serde(rename = "type")]
    pub r#type: String,
    #[serde(default)]
    pub frequency: String,
    #[serde(rename = "timeOfDay", alias = "time_of_day", default)]
    pub time_of_day: Option<String>,
    #[serde(rename = "nextDate", alias = "next_date", default)]
    pub next_date: Option<String>,
    #[serde(rename = "isActive", alias = "is_active", default = "default_true")]
    pub is_active: bool,
}

#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
pub struct SyncPayload {
    #[serde(rename = "lastUpdated", alias = "last_updated")]
    pub last_updated: i64,
    #[serde(rename = "birthControl", alias = "birth_control", default)]
    pub birth_control: Option<SyncBirthControl>,
    #[serde(default)]
    pub entries: Vec<SyncEntry>,
    #[serde(default)]
    pub reminders: Vec<SyncReminder>,
    #[serde(rename = "pregnancyTests", alias = "pregnancy_tests", default)]
    pub pregnancy_tests: Vec<SyncPregnancyTest>,
    #[serde(rename = "partnerPermissions", alias = "partner_permissions", deserialize_with = "deserialize_permissions", default)]
    pub partner_permissions: Option<PartnerPermissions>,
}

pub fn calculate_phase_internal(last_period_date: &str, avg_cycle_length: i32, today: NaiveDate) -> String {
    if last_period_date.is_empty() || last_period_date.len() > 64 {
        return "Unknown".to_string();
    }
    let parsed_date = match NaiveDate::parse_from_str(last_period_date, "%Y-%m-%d") {
        Ok(d) => d,
        Err(_) => return "Unknown".to_string(),
    };
    let cycle_length = avg_cycle_length.clamp(15, 90) as i64;
    let diff = today.signed_duration_since(parsed_date).num_days();
    if diff < 0 {
        return "Unknown".to_string();
    }
    let day_of_cycle = (diff % cycle_length) + 1;
    let ovulation_day = cycle_length - 14;
    
    if day_of_cycle <= 5 {
        "Menstrual".to_string()
    } else if day_of_cycle < ovulation_day - 3 {
        "Follicular".to_string()
    } else if day_of_cycle <= ovulation_day + 2 {
        "Ovulation".to_string()
    } else if day_of_cycle <= cycle_length {
        "Luteal".to_string()
    } else {
        "Unknown".to_string()
    }
}

pub fn predict_next_period_internal(last_period_date: &str, avg_cycle_length: i32) -> String {
    if last_period_date.is_empty() || last_period_date.len() > 64 {
        return "".to_string();
    }
    let parsed_date = match NaiveDate::parse_from_str(last_period_date, "%Y-%m-%d") {
        Ok(d) => d,
        Err(_) => return "".to_string(),
    };
    let cycle_length = avg_cycle_length.clamp(15, 90);
    let next_date = parsed_date + Duration::days(cycle_length as i64);
    next_date.format("%Y-%m-%d").to_string()
}

pub fn analyze_cycle_history_internal(dates_csv: &str) -> String {
    if dates_csv.is_empty() || dates_csv.len() > 1024 * 64 {
        let default_pred = prediction_engine::CyclePrediction {
            estimated_length_days: 28,
            variance_days: 0,
            is_irregular: false,
        };
        return serde_json::to_string(&default_pred).unwrap_or_default();
    }
    let dates: Vec<NaiveDate> = dates_csv
        .split(',')
        .map(|s| s.trim())
        .filter(|s| !s.is_empty())
        .filter_map(|s| NaiveDate::parse_from_str(s, "%Y-%m-%d").ok())
        .collect();

    if dates.len() < 2 {
        let default_pred = prediction_engine::CyclePrediction {
            estimated_length_days: 28,
            variance_days: 0,
            is_irregular: false,
        };
        return serde_json::to_string(&default_pred).unwrap_or_default();
    }

    let mut intervals: Vec<u32> = Vec::new();
    for window in dates.windows(2) {
        let diff = window[1].signed_duration_since(window[0]).num_days();
        if diff > 0 && diff < 120 {
            intervals.push(diff as u32);
        }
    }

    let prediction = prediction_engine::predict_irregular_cycle(&intervals);
    serde_json::to_string(&prediction).unwrap_or_default()
}

pub fn calculate_next_cycle_native_internal(history_json: &str) -> String {
    if history_json.is_empty() || history_json.len() > 1024 * 1024 {
        return r#"{"currentDayOfCycle":1,"fertileWindow":{},"ovulationDate":""}"#.to_string();
    }
    let entries: Vec<SyncEntry> = match serde_json::from_str(history_json) {
        Ok(e) => e,
        Err(_) => return r#"{"currentDayOfCycle":1,"fertileWindow":{},"ovulationDate":""}"#.to_string(),
    };
    let mut dates: Vec<NaiveDate> = entries
        .iter()
        .filter_map(|e| NaiveDate::parse_from_str(&e.date, "%Y-%m-%d").ok())
        .collect();
    dates.sort();
    dates.dedup();

    let mut lengths = Vec::new();
    for window in dates.windows(2) {
        let diff = window[1].signed_duration_since(window[0]).num_days();
        if (15..=90).contains(&diff) {
            lengths.push(diff as u32);
        }
    }

    let prediction = prediction_engine::predict_irregular_cycle(&lengths);
    let avg_len = prediction.estimated_length_days as i64;
    let today = chrono::Local::now().date_naive();
    let current_day = if let Some(&last_date) = dates.last() {
        let diff = today.signed_duration_since(last_date).num_days();
        if diff >= 0 { (diff % avg_len) + 1 } else { 1 }
    } else {
        1
    };

    #[derive(Serialize)]
    struct NativeResult {
        #[serde(rename = "currentDayOfCycle")]
        current_day_of_cycle: i64,
        #[serde(rename = "estimatedLength")]
        estimated_length: u32,
        #[serde(rename = "isIrregular")]
        is_irregular: bool,
    }

    let res = NativeResult {
        current_day_of_cycle: current_day,
        estimated_length: prediction.estimated_length_days,
        is_irregular: prediction.is_irregular,
    };
    serde_json::to_string(&res).unwrap_or_default()
}

pub fn generate_filtered_payload_internal(
    last_updated: i64,
    birth_control_json: Option<String>,
    entries_json: String,
    partner_id: &str,
    shared_key_base64: &str,
) -> Vec<u8> {
    generate_filtered_payload_with_extras_internal(
        last_updated,
        birth_control_json,
        entries_json,
        None,
        None,
        partner_id,
        shared_key_base64,
    )
}

pub fn generate_filtered_payload_with_extras_internal(
    last_updated: i64,
    birth_control_json: Option<String>,
    entries_json: String,
    reminders_json: Option<String>,
    pregnancy_tests_json: Option<String>,
    partner_id: &str,
    shared_key_base64: &str,
) -> Vec<u8> {
    if entries_json.len() > crate::crypto::MAX_JNI_INPUT_BYTES
        || partner_id.len() > 256
        || shared_key_base64.len() > 256
    {
        return vec![];
    }
    let perms = get_partner_permissions_internal();

    let bc: Option<SyncBirthControl> = if perms.share_birth_control {
        birth_control_json.and_then(|s| serde_json::from_str(&s).ok())
    } else {
        None
    };

    let entries: Vec<SyncEntry> = if perms.share_cycle_phase || perms.share_flow_intensity || perms.share_symptoms_moods || perms.share_sexual_activity {
        let raw_entries: Vec<SyncEntry> = serde_json::from_str(&entries_json).unwrap_or_default();
        raw_entries
            .into_iter()
            .map(|mut e| {
                if !perms.share_flow_intensity {
                    e.intensity = 0;
                }
                if !perms.share_symptoms_moods {
                    e.symptoms = None;
                    e.pain_level = None;
                    e.notes = None;
                }
                if !perms.share_sexual_activity {
                    e.sexual_relations = None;
                }
                e
            })
            .collect()
    } else {
        vec![]
    };

    let reminders: Vec<SyncReminder> = reminders_json
        .and_then(|s| serde_json::from_str(&s).ok())
        .unwrap_or_default();

    let pregnancy_tests: Vec<SyncPregnancyTest> = if perms.share_pregnancy_tests {
        pregnancy_tests_json
            .and_then(|s| serde_json::from_str(&s).ok())
            .unwrap_or_default()
    } else {
        vec![]
    };

    let payload = SyncPayload {
        last_updated,
        birth_control: bc,
        entries,
        reminders,
        pregnancy_tests,
        partner_permissions: Some(perms),
    };

    let json_str = match serde_json::to_string(&payload) {
        Ok(s) => s,
        Err(_) => return vec![],
    };

    let key_vec = match general_purpose::STANDARD.decode(shared_key_base64) {
        Ok(v) => Zeroizing::new(v),
        Err(_) => return vec![],
    };
    if key_vec.len() != 32 {
        return vec![];
    }
    crate::crypto::encrypt_data(&json_str, partner_id, &key_vec)
}
