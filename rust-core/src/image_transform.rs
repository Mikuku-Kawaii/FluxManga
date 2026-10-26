//! Strip layout adapted from JMComic-Crawler-Python, pinned in jm-image-NOTICE.txt.
//! Pixels stay with Android; this API only maps a requested destination viewport.
use crate::source::SourceError;

#[derive(Debug, PartialEq, Eq, uniffi::Record)]
pub struct ImageStripRegion {
    pub source_top: u32,
    pub target_top: u32,
    pub height: u32,
}

#[uniffi::export]
pub fn image_strip_plan(
    height: u32,
    strips: u32,
    top: u32,
    bottom: u32,
) -> Result<Vec<ImageStripRegion>, SourceError> {
    if height == 0
        || top >= bottom
        || bottom > height
        || (strips != 0 && (strips < 2 || strips > 20 || strips % 2 != 0 || strips > height))
    {
        return Err(SourceError::InvalidRequest);
    }
    if strips == 0 {
        return Ok(vec![ImageStripRegion {
            source_top: top,
            target_top: 0,
            height: bottom - top,
        }]);
    }
    let base = height / strips;
    let remainder = height % strips;
    let mut result = Vec::with_capacity(strips as usize);
    for i in 0..strips {
        let source = height - base * (i + 1) - remainder;
        let target = base * i + if i == 0 { 0 } else { remainder };
        let length = base + if i == 0 { remainder } else { 0 };
        let start = target.max(top);
        let end = (target + length).min(bottom);
        if start < end {
            result.push(ImageStripRegion {
                source_top: source + start - target,
                target_top: start - top,
                height: end - start,
            });
        }
    }
    Ok(result)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn matches_pinned_upstream_numbered_pixel_images_and_viewports() {
        let fixtures: serde_json::Value =
            serde_json::from_str(include_str!("../tests/fixtures/jm-strip-vectors.json")).unwrap();
        for fixture in fixtures.as_array().unwrap() {
            let height = fixture["height"].as_u64().unwrap() as u32;
            let strips = fixture["strips"].as_u64().unwrap() as u32;
            let expected: Vec<u32> = fixture["rows"]
                .as_array()
                .unwrap()
                .iter()
                .map(|row| row.as_u64().unwrap() as u32)
                .collect();
            assert_eq!(rows(height, strips, 0, height), expected);
            for top in 0..height {
                let bottom = (top + 31).min(height);
                assert_eq!(
                    rows(height, strips, top, bottom),
                    expected[top as usize..bottom as usize]
                );
            }
        }
    }
    fn rows(height: u32, strips: u32, top: u32, bottom: u32) -> Vec<u32> {
        let plan = image_strip_plan(height, strips, top, bottom).unwrap();
        let mut result = Vec::new();
        for part in plan {
            assert_eq!(part.target_top as usize, result.len());
            assert!(part.source_top + part.height <= height);
            result.extend(part.source_top..part.source_top + part.height);
        }
        assert_eq!(result.len(), (bottom - top) as usize);
        result
    }
    #[test]
    fn fixed_numbered_rows_preserve_remainder_in_first_destination_strip() {
        // Independent hand-calculated golden: 13 rows, 4 strips, remainder 1.
        assert_eq!(
            rows(13, 4, 0, 13),
            vec![9, 10, 11, 12, 6, 7, 8, 3, 4, 5, 0, 1, 2]
        );
        assert_eq!(rows(13, 4, 2, 11), vec![11, 12, 6, 7, 8, 3, 4, 5, 0]);
        assert_eq!(rows(13, 0, 2, 11), (2..11).collect::<Vec<_>>());
    }
    #[test]
    fn every_pixel_row_occurs_once_and_partial_regions_match_full_image() {
        for n in (2..=20).step_by(2) {
            for h in [n, n + 1, 723, 724, 6003] {
                let full = rows(h, n, 0, h);
                let mut sorted = full.clone();
                sorted.sort_unstable();
                assert_eq!(sorted, (0..h).collect::<Vec<_>>());
                for top in [0, 1, h / 2, h - 1] {
                    let bottom = (top + 511).min(h);
                    assert_eq!(rows(h, n, top, bottom), full[top as usize..bottom as usize]);
                }
            }
        }
    }
    #[test]
    fn invalid_metadata_is_rejected_without_a_partial_plan() {
        for (h, n, t, b) in [
            (0, 0, 0, 1),
            (12, 4, 1, 1),
            (12, 4, 0, 13),
            (12, 1, 0, 12),
            (12, 3, 0, 12),
            (12, 22, 0, 12),
            (1, 2, 0, 1),
            (12, 4, 9, 2),
        ] {
            assert_eq!(
                image_strip_plan(h, n, t, b),
                Err(SourceError::InvalidRequest)
            );
        }
    }
    #[test]
    fn maximum_height_does_not_overflow() {
        let p = image_strip_plan(u32::MAX, 20, u32::MAX - 1, u32::MAX).unwrap();
        assert_eq!(p.len(), 1);
        assert_eq!(p[0].height, 1);
        assert_eq!(p[0].source_top, u32::MAX / 20 - 1);
    }
}
