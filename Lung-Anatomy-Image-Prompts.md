# Lung Anatomy Image Generation Prompts

> **How to use:** Send each prompt below to your image generation AI.
> Save the outputs as: `placeholder_anterior_right.png`, `placeholder_anterior_left.png`,
> `placeholder_posterior_right.png`, `placeholder_posterior_left.png`
> and place them in `lungs-app/src/main/res/drawable/`
>
> **Why positioning matters:** The app places 4 circular tappable buttons on top of these images
> at exact pixel percentages (xFraction × imageWidth, yFraction × imageHeight).
> The anatomy in each image MUST align with those positions or the buttons will land on the wrong body part.
>
> **Required image spec:** Portrait orientation, aspect ratio 3:4 (e.g. 600×800 px or 900×1200 px),
> PNG format, clean white or very light grey background.

---

## Image 1 — `placeholder_anterior_right.png`
**Front of body, RIGHT lung side**

```
Create a clean medical illustration of the FRONT of a human torso for a lung auscultation reference app.

Style: Flat medical diagram, clean outlines, light background (#F8F9FA or white). No shading. Anatomical chart style. Portrait orientation, 3:4 aspect ratio (600×800 px).

View: The viewer is looking at the FRONT of the patient's chest. The patient's RIGHT side is on the RIGHT side of the image (anatomical orientation, not radiological).

Body: Show a gender-neutral torso from the neck/collarbone down to just below the ribcage. Draw clear outlines of: the clavicles at the top, the sternum running vertically in the center, and 6–7 visible rib outlines on the right side of the chest. Keep the left side of the chest empty/plain (this is a right-lung focused view). Teal outline color (#2ABFBF) for anatomy lines, light fill.

Place exactly 4 numbered marker dots on the image at these percentage positions (measured from top-left corner of image):

  Dot 1 — Label "Apex"     — position: 50% from left, 18% from top  → near the top center of the chest, just below the clavicle notch, at the apex of the right lung (slightly right of center at the first rib level)
  Dot 2 — Label "Superior" — position: 65% from left, 32% from top  → right chest, 2nd intercostal space, mid-clavicular line on the right
  Dot 3 — Label "Middle"   — position: 65% from left, 50% from top  → right chest, 4th intercostal space, mid-clavicular line on the right
  Dot 4 — Label "Inferior" — position: 65% from left, 68% from top  → right chest, 6th intercostal space, mid-clavicular line on the right

Marker style: Filled teal circles (#2ABFBF), 18px diameter, white number inside (1, 2, 3, 4). Small white label text below each dot in dark grey.

Title text at top: "Anterior Right" in teal, bold, 16sp. Do NOT add any border or card background.
```

---

## Image 2 — `placeholder_anterior_left.png`
**Front of body, LEFT lung side**

```
Create a clean medical illustration of the FRONT of a human torso for a lung auscultation reference app.

Style: Flat medical diagram, clean outlines, light background (#F8F9FA or white). No shading. Anatomical chart style. Portrait orientation, 3:4 aspect ratio (600×800 px).

View: The viewer is looking at the FRONT of the patient's chest. The patient's LEFT side is on the LEFT side of the image (anatomical orientation, not radiological).

Body: Show a gender-neutral torso from the neck/collarbone down to just below the ribcage. Draw clear outlines of: the clavicles at the top, the sternum running vertically in the center, and 6–7 visible rib outlines on the LEFT side of the chest. Keep the right side of the chest empty/plain (this is a left-lung focused view). Teal outline color (#2ABFBF) for anatomy lines, light fill.

Place exactly 4 numbered marker dots on the image at these percentage positions (measured from top-left corner of image):

  Dot 1 — Label "Apex"     — position: 50% from left, 18% from top  → near the top center of the chest, just below the clavicle notch, at the apex of the left lung (slightly left of center at the first rib level)
  Dot 2 — Label "Superior" — position: 35% from left, 32% from top  → left chest, 2nd intercostal space, mid-clavicular line on the left
  Dot 3 — Label "Middle"   — position: 35% from left, 50% from top  → left chest, 4th intercostal space, mid-clavicular line on the left
  Dot 4 — Label "Inferior" — position: 35% from left, 68% from top  → left chest, 6th intercostal space, mid-clavicular line on the left

Marker style: Filled teal circles (#2ABFBF), 18px diameter, white number inside (1, 2, 3, 4). Small white label text below each dot in dark grey.

Title text at top: "Anterior Left" in teal, bold, 16sp. Do NOT add any border or card background.
```

---

## Image 3 — `placeholder_posterior_right.png`
**Back of body, RIGHT lung side**

```
Create a clean medical illustration of the BACK of a human torso for a lung auscultation reference app.

Style: Flat medical diagram, clean outlines, light background (#F8F9FA or white). No shading. Anatomical chart style. Portrait orientation, 3:4 aspect ratio (600×800 px).

View: The viewer is looking at the BACK of the patient. The patient's RIGHT side is on the RIGHT side of the image. (When viewing the back, the patient's right is on the viewer's right.)

Body: Show a gender-neutral torso from the base of the neck down to just below the lower ribs, viewed from behind. Draw clear outlines of: the spine running vertically in the center, the right scapula (shoulder blade) visible in the upper right portion of the back, and implied rib outlines on the right side. Keep the left side plain/empty (right-lung focused). Teal outline color (#2ABFBF) for anatomy lines, light fill.

Place exactly 4 numbered marker dots on the image at these percentage positions (measured from top-left corner of image):

  Dot 1 — Label "Apex"     — position: 65% from left, 15% from top  → right upper back, above the scapula/suprascapular area, near the apex of the right lung posteriorly
  Dot 2 — Label "Superior" — position: 65% from left, 33% from top  → right mid-upper back, interscapular area, around T4–T5 vertebral level on the right
  Dot 3 — Label "Middle"   — position: 65% from left, 52% from top  → right mid back, below the scapula tip, around T6–T8 level on the right
  Dot 4 — Label "Inferior" — position: 65% from left, 70% from top  → right lower back, around T9–T10 level, above the lower costal margin on the right

Marker style: Filled teal circles (#2ABFBF), 18px diameter, white number inside (1, 2, 3, 4). Small white label text below each dot in dark grey.

Title text at top: "Posterior Right" in teal, bold, 16sp. Do NOT add any border or card background.
```

---

## Image 4 — `placeholder_posterior_left.png`
**Back of body, LEFT lung side**

```
Create a clean medical illustration of the BACK of a human torso for a lung auscultation reference app.

Style: Flat medical diagram, clean outlines, light background (#F8F9FA or white). No shading. Anatomical chart style. Portrait orientation, 3:4 aspect ratio (600×800 px).

View: The viewer is looking at the BACK of the patient. The patient's LEFT side is on the LEFT side of the image. (When viewing the back, the patient's left is on the viewer's left.)

Body: Show a gender-neutral torso from the base of the neck down to just below the lower ribs, viewed from behind. Draw clear outlines of: the spine running vertically in the center, the left scapula (shoulder blade) visible in the upper left portion of the back, and implied rib outlines on the left side. Keep the right side plain/empty (left-lung focused). Teal outline color (#2ABFBF) for anatomy lines, light fill.

Place exactly 4 numbered marker dots on the image at these percentage positions (measured from top-left corner of image):

  Dot 1 — Label "Apex"     — position: 35% from left, 15% from top  → left upper back, above the scapula/suprascapular area, near the apex of the left lung posteriorly
  Dot 2 — Label "Superior" — position: 35% from left, 33% from top  → left mid-upper back, interscapular area, around T4–T5 vertebral level on the left
  Dot 3 — Label "Middle"   — position: 35% from left, 52% from top  → left mid back, below the scapula tip, around T6–T8 level on the left
  Dot 4 — Label "Inferior" — position: 35% from left, 70% from top  → left lower back, around T9–T10 level, above the lower costal margin on the left

Marker style: Filled teal circles (#2ABFBF), 18px diameter, white number inside (1, 2, 3, 4). Small white label text below each dot in dark grey.

Title text at top: "Posterior Left" in teal, bold, 16sp. Do NOT add any border or card background.
```

---

## After you have the images

1. Save all 4 files as `.png` in `lungs-app/src/main/res/drawable/`:
   - `placeholder_anterior_right.png`
   - `placeholder_anterior_left.png`
   - `placeholder_posterior_right.png`
   - `placeholder_posterior_left.png`

2. These replace the current colored-rectangle XML placeholders (same filenames — Android will use PNGs over XMLs of the same name if both exist, so you can also just delete the old XML files of the same name).

3. The overlay tappable buttons in `PlacementFragment` are placed at the **exact same percentage positions** as the dots in the images, so the buttons will land precisely on the anatomical markers.

4. If the AI-generated image anatomy looks slightly off (buttons land on wrong spot), adjust the `xFraction`/`yFraction` values in `LungPoint.kt` to match the actual pixel positions in your images — or regenerate the image with corrected dot positions.

---

## Quick position reference table (for regenerating or correcting)

| Region | Point | Code | X% | Y% | Anatomy landmark |
|---|---|---|---|---|---|
| Anterior Right | Apex | aar | 50% | 18% | Suprasternal / 1st rib, center |
| Anterior Right | Superior | aslr | 65% | 32% | 2nd ICS, right mid-clavicular |
| Anterior Right | Middle | amlr | 65% | 50% | 4th ICS, right mid-clavicular |
| Anterior Right | Inferior | ailr | 65% | 68% | 6th ICS, right mid-clavicular |
| Anterior Left | Apex | aal | 50% | 18% | Suprasternal / 1st rib, center |
| Anterior Left | Superior | asll | 35% | 32% | 2nd ICS, left mid-clavicular |
| Anterior Left | Middle | amll | 35% | 50% | 4th ICS, left mid-clavicular |
| Anterior Left | Inferior | aill | 35% | 68% | 6th ICS, left mid-clavicular |
| Posterior Right | Apex | par | 65% | 15% | Suprascapular, right |
| Posterior Right | Superior | pslr | 65% | 33% | Interscapular T4–T5, right |
| Posterior Right | Middle | pmlr | 65% | 52% | Infrascapular T6–T8, right |
| Posterior Right | Inferior | pilr | 65% | 70% | T9–T10, right |
| Posterior Left | Apex | pal | 35% | 15% | Suprascapular, left |
| Posterior Left | Superior | psll | 35% | 33% | Interscapular T4–T5, left |
| Posterior Left | Middle | pmll | 35% | 52% | Infrascapular T6–T8, left |
| Posterior Left | Inferior | pill | 35% | 70% | T9–T10, left |

ICS = Intercostal Space
