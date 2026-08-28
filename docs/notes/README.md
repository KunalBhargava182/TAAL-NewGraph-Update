# Notes index

Loose project-status / planning / reference markdown files, previously scattered at
the repo root, consolidated here so they're in one place. `README.md` (repo root) and
`docs/pcg-segmentation/` (that module's own docs) were left where they were.

| File | Title |
|---|---|
| [MASTER_HANDOFF.md](MASTER_HANDOFF.md) | **Start here — full-code edition.** Single-file, zero-context handoff (~7,200 lines) covering `taal-core`, `taal-ui-kit`, the `app` module's full architecture/navigation, and complete embedded source code (not summaries) for all 6 waveform/graph implementations — including every file of the Calibrated and FullTimeOn screen systems, verified directly against live source. Deliberately excludes `lungs-app`/`visualizertaal-app`/PCG segmentation/`stemz-app` by request. Written 2026-08-14. |
| [WAVEFORM_GRAPH_AND_GRID_REFERENCE.md](WAVEFORM_GRAPH_AND_GRID_REFERENCE.md) | Deep reference for the 4 *original* waveform/grid implementations (Recording, Player, Test Recording, Test Player) only, verified line-by-line against source 2026-08-18. Now superseded for orientation by MASTER_HANDOFF.md, but still the most detailed source for screens A-D specifically. |
| [AI_DOWNSAMPLING_PIPELINE.md](AI_DOWNSAMPLING_PIPELINE.md) | AI Downsampling Pipeline |
| [BUILD_SUCCESS.md](BUILD_SUCCESS.md) | TAAL SDK - Build Successful! |
| [CODEBASE_MAP.md](CODEBASE_MAP.md) | TAAL App — Complete Codebase Map *(stale — see [[project_uikit_parity_update]] / [[project_app_nav_entry]])* |
| [CONTEXT_FOR_PLANNING.md](CONTEXT_FOR_PLANNING.md) | TAAL Demo App — Full Project Context (Planning Reference) *(stale — see above)* |
| [ECG_Paper_View.md](ECG_Paper_View.md) | ECG Paper View |
| [IMPLEMENTATION_SUMMARY.md](IMPLEMENTATION_SUMMARY.md) | TAAL SDK Rebuild - Implementation Summary |
| [Lungs-App.md](Lungs-App.md) | Lungs Auscultation App — Complete Reference |
| [MuseD App Graph.md](MuseD%20App%20Graph.md) | MuseD App – `waveformChart` Documentation |
| [QUICK_START.md](QUICK_START.md) | TAAL SDK - Quick Start Guide |
| [RECORDING_RELIABILITY_FIXES_2026-08.md](RECORDING_RELIABILITY_FIXES_2026-08.md) | Recording Reliability Fixes — August 2026 |
| [RecorderandPLayerScreenwithallimp.md](RecorderandPLayerScreenwithallimp.md) | TAAL SDK — Recording & Player Screen Implementation Guide |
| [TAAL_SDK_Correct_Integration_Guide.md](TAAL_SDK_Correct_Integration_Guide.md) | TAAL SDK — Correct Integration Guide (Verified Working) |
| [TAAL_SDK_Integration_Guide.md](TAAL_SDK_Integration_Guide.md) | TAAL Digital Stethoscope SDK — Integration Guide |
| [TAAL_SDK_Integration_Guide_v2.0.0.md](TAAL_SDK_Integration_Guide_v2.0.0.md) | TAAL Digital Stethoscope SDK (v2.0.0) |
| [TaalinTABS.md](TaalinTABS.md) | TAAL App — Tablet Compatibility Guide |
| [UI_IMPLEMENTATION_GUIDE.md](UI_IMPLEMENTATION_GUIDE.md) | TAAL Custom UI Build Guide |
| [UNIT_TEST_REPORT.md](UNIT_TEST_REPORT.md) | Unit Test Report — TAAL SDK & UI Kit |
| [VisualizerTaal-App.md](VisualizerTaal-App.md) | VisualizerTaal-App — Complete Reference |
| [denoiser.md](denoiser.md) | Lungs App — Denoiser Feature Reference |
| [sdk_guide_pdf_update_prompt.md](sdk_guide_pdf_update_prompt.md) | Prompt to paste into Claude web |

## Elsewhere (not moved here)

- `README.md` — repo root, kept there so GitHub renders it on the repo homepage.
- `docs/pcg-segmentation/` — `API.md`, `APP_INTEGRATION_STATUS.md`, `AUDIO_CAPTURE.md`,
  `INTEGRATION.md`, `MODEL_CARD.md`, `PROVENANCE.md`, `README.md` — that module's own docs.
- `graphify-out/` — generated, gitignored; `GRAPH_REPORT.md` and query logs under `memory/`.
