# Lungs Auscultation — Placeholder Image Prompts for Gemini

> **Usage**: Feed each prompt to Gemini image generation.
> Save outputs as PNG into `lungs-app/src/main/res/drawable/`.
> No auscultation points — the app overlays interactive buttons on top at runtime.

---

## Image 1 — `placeholder_anterior_right.png`

**Prompt:**

> Create a clean medical illustration of the FRONT of a human torso for a lung auscultation reference app.
>
> Style: Flat medical diagram, clean outlines, light background (#F8F9FA or white). No shading. Anatomical chart style. Portrait orientation, 3:4 aspect ratio (600×800 px).
>
> View: The viewer is looking at the FRONT of the patient's chest. The patient's RIGHT side is on the RIGHT side of the image (anatomical orientation, not radiological). The patient's LEFT side is on the LEFT side of the image.
>
> Body: Show a gender-neutral torso from the neck/collarbone down to just below the ribcage. Draw clear outlines of: the clavicles at the top, the sternum running vertically in the center, and 6–7 visible rib outlines on the right side of the chest. Keep the left side of the chest minimally detailed (plain outline only — this is a right-lung focused view). Use teal (#2ABFBF) for all anatomy outlines, light fill.
>
> Add a subtle teal tint or soft teal shaded overlay on the right lung region only to indicate it is the active region. The left lung area has no tint.
>
> Orientation labels: Place a bold "R" label on the right side of the image and a bold "L" label on the left side of the image, in dark grey, clearly readable.
>
> Title text at top center: "Anterior Right" in teal, bold, 16sp.
>
> Do NOT add any dots, numbered markers, stethoscope icons, or any other annotation. No border or card background.

---

## Image 2 — `placeholder_anterior_left.png`

**Prompt:**

> Create a clean medical illustration of the FRONT of a human torso for a lung auscultation reference app.
>
> Style: Flat medical diagram, clean outlines, light background (#F8F9FA or white). No shading. Anatomical chart style. Portrait orientation, 3:4 aspect ratio (600×800 px).
>
> View: The viewer is looking at the FRONT of the patient's chest. The patient's RIGHT side is on the RIGHT side of the image (anatomical orientation, not radiological). The patient's LEFT side is on the LEFT side of the image.
>
> Body: Show a gender-neutral torso from the neck/collarbone down to just below the ribcage. Draw clear outlines of: the clavicles at the top, the sternum running vertically in the center, and 6–7 visible rib outlines on the left side of the chest. Also draw a faint cardiac silhouette (heart outline) in the lower-left chest region, slightly left of the sternum. Keep the right side of the chest minimally detailed (plain outline only — this is a left-lung focused view). Use teal (#2ABFBF) for all anatomy outlines, light fill.
>
> Add a subtle teal tint or soft teal shaded overlay on the left lung region only to indicate it is the active region. The right lung area has no tint.
>
> Orientation labels: Place a bold "R" label on the right side of the image and a bold "L" label on the left side of the image, in dark grey, clearly readable.
>
> Title text at top center: "Anterior Left" in teal, bold, 16sp.
>
> Do NOT add any dots, numbered markers, stethoscope icons, or any other annotation. No border or card background.

---

## Image 3 — `placeholder_posterior_right.png`

**Prompt:**

> Create a clean medical illustration of the BACK of a human torso for a lung auscultation reference app.
>
> Style: Flat medical diagram, clean outlines, light background (#F8F9FA or white). No shading. Anatomical chart style. Portrait orientation, 3:4 aspect ratio (600×800 px).
>
> View: The viewer is looking at the BACK of the patient. The patient is facing away. The patient's RIGHT side is on the RIGHT side of the image. The patient's LEFT side is on the LEFT side of the image.
>
> Body: Show a gender-neutral torso from the shoulders down to just below the lower ribcage (back view). Draw clear outlines of: the spine running vertically down the center, the right scapula in the upper-right area, and 6–7 visible rib outlines on the right side of the back. Keep the left side of the back minimally detailed (plain outline only — this is a right posterior lung focused view). Use teal (#2ABFBF) for all anatomy outlines, light fill.
>
> Add a subtle teal tint or soft teal shaded overlay on the right posterior lung region only (to the right of the spine) to indicate it is the active region. The left side has no tint.
>
> Orientation labels: Place a bold "R" label on the right side of the image and a bold "L" label on the left side of the image, in dark grey, clearly readable.
>
> Title text at top center: "Posterior Right" in teal, bold, 16sp.
>
> Do NOT add any dots, numbered markers, stethoscope icons, or any other annotation. No border or card background.

---

## Image 4 — `placeholder_posterior_left.png`

**Prompt:**

> Create a clean medical illustration of the BACK of a human torso for a lung auscultation reference app.
>
> Style: Flat medical diagram, clean outlines, light background (#F8F9FA or white). No shading. Anatomical chart style. Portrait orientation, 3:4 aspect ratio (600×800 px).
>
> View: The viewer is looking at the BACK of the patient. The patient is facing away. The patient's RIGHT side is on the RIGHT side of the image. The patient's LEFT side is on the LEFT side of the image.
>
> Body: Show a gender-neutral torso from the shoulders down to just below the lower ribcage (back view). Draw clear outlines of: the spine running vertically down the center, the left scapula in the upper-left area, and 6–7 visible rib outlines on the left side of the back. Keep the right side of the back minimally detailed (plain outline only — this is a left posterior lung focused view). Use teal (#2ABFBF) for all anatomy outlines, light fill.
>
> Add a subtle teal tint or soft teal shaded overlay on the left posterior lung region only (to the left of the spine) to indicate it is the active region. The right side has no tint.
>
> Orientation labels: Place a bold "R" label on the right side of the image and a bold "L" label on the left side of the image, in dark grey, clearly readable.
>
> Title text at top center: "Posterior Left" in teal, bold, 16sp.
>
> Do NOT add any dots, numbered markers, stethoscope icons, or any other annotation. No border or card background.

---

## Notes

- **Target size**: 600×800 px (portrait, 3:4).
- **Teal color**: `#2ABFBF` — matches `teal_primary` in the app's `colors.xml`.
- **After generation**: Place the 4 PNGs in `lungs-app/src/main/res/drawable/` with exact filenames above.
- **Runtime overlay**: The app draws interactive point buttons on top of these images programmatically in `PlacementFragment.addPointButton()`.
