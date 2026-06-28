use serde::{Deserialize, Serialize};

#[derive(Serialize, Deserialize, Debug)]
pub struct CyclePrediction {
    pub estimated_length_days: u32,
    pub variance_days: u32,
    pub is_irregular: bool,
}

/// Implements a robust statistical engine combining Exponential Moving Average (EMA)
/// and Median Absolute Deviation (MAD) for over-dispersed (irregular) cycle data.
/// This approach gives more weight to recent cycles while clipping extreme outliers.
pub fn predict_irregular_cycle(historical_lengths: &[u32]) -> CyclePrediction {
    let n = historical_lengths.len() as f64;
    
    if n < 3.0 {
        return CyclePrediction {
            estimated_length_days: 28,
            variance_days: 0,
            is_irregular: false,
        };
    }

    // 1. Calculate Mean and Variance
    let sum: f64 = historical_lengths.iter().map(|&x| x as f64).sum();
    let mean = sum / n;
    
    let variance: f64 = historical_lengths.iter().map(|&x| {
        let diff = (x as f64) - mean;
        diff * diff
    }).sum::<f64>() / n;
    
    let std_dev = variance.sqrt();
    
    // 2. Calculate Dispersion Index
    let dispersion_index = if mean > 0.0 { variance / mean } else { 0.0 };
    let is_irregular = dispersion_index > 1.5 || std_dev > 7.0;

    // 3. Calculate Median and MAD (Median Absolute Deviation)
    let mut sorted_lengths = historical_lengths.to_vec();
    sorted_lengths.sort_unstable();
    
    let median = if sorted_lengths.len() % 2 == 0 {
        let mid = sorted_lengths.len() / 2;
        (sorted_lengths[mid - 1] + sorted_lengths[mid]) as f64 / 2.0
    } else {
        sorted_lengths[sorted_lengths.len() / 2] as f64
    };

    let mut abs_deviations: Vec<f64> = historical_lengths.iter()
        .map(|&x| ((x as f64) - median).abs())
        .collect();
    abs_deviations.sort_by(|a, b| a.partial_cmp(b).unwrap_or(std::cmp::Ordering::Equal));

    let mad = if abs_deviations.len() % 2 == 0 {
        let mid = abs_deviations.len() / 2;
        (abs_deviations[mid - 1] + abs_deviations[mid]) / 2.0
    } else {
        abs_deviations[abs_deviations.len() / 2]
    };

    // Safe bounds for Winsorization (clipping extreme outliers)
    // 1.4826 * MAD approximates standard deviation for a normal distribution
    let mad_std_dev = mad * 1.4826;
    let threshold = if mad_std_dev > 2.0 { mad_std_dev * 2.0 } else { 4.0 };
    
    let lower_bound = (median - threshold).max(15.0);
    let upper_bound = (median + threshold).min(90.0);

    // 4. Exponential Moving Average (EMA) with Winsorization
    let alpha = 0.4; // Weight for the most recent observation
    
    // Start EMA with the first available valid data point
    let mut ema = (historical_lengths[0] as f64).clamp(lower_bound, upper_bound);
    
    for &len in historical_lengths.iter().skip(1) {
        // Clip outliers before feeding them into the EMA
        let clipped_len = (len as f64).clamp(lower_bound, upper_bound);
        ema = (alpha * clipped_len) + ((1.0 - alpha) * ema);
    }

    // Blend EMA with median if highly irregular to provide stability
    let prediction = if is_irregular {
        (ema * 0.6) + (median * 0.4)
    } else {
        ema
    };

    CyclePrediction {
        estimated_length_days: prediction.round().clamp(15.0, 90.0) as u32,
        variance_days: std_dev.round() as u32,
        is_irregular,
    }
}
