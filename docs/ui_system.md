# VeilSpace UI System

This document defines the UI rules for every VeilSpace-owned surface. The disguise entry is intentionally excluded and must remain visually independent.

## Foundations

- Use semantic colors from `res/values/colors.xml`. Feature layouts must not introduce page-specific background or text colors.
- Use `space_background` for the window, `space_surface` for repeated content, and `space_surface_high` for modal surfaces.
- Use cyan for primary actions, violet for secondary emphasis, and red only for destructive or failed states.
- Page spacing follows an 8dp base grid. Supported corner radii are 12dp, 16dp, 20dp, 24dp, and 28dp for modal surfaces.
- Page titles use `TextAppearance.PrivacySpace.PageTitle`; section titles use `TextAppearance.PrivacySpace.SectionTitle`.

## Components

- Every main page uses `AnimatedSpaceBackgroundView` as the first child and keeps content in a `page_content` container.
- Icon commands use `Widget.PrivacySpace.IconButton` or its `Primary` variant.
- Primary, tonal, text, and destructive actions use the matching `Widget.PrivacySpace` button styles.
- Repeated rows use a translucent surface, 20dp radius, 1dp semantic outline, and a minimum 48dp touch target.
- Binary settings use `MaterialSwitch` with `space_switch_thumb` and `space_switch_track`.
- Option sets use a `MaterialButtonToggleGroup` or `TabLayout` with the shared segmented container.

## Motion

- Page navigation uses the animations declared in `nav_graph.xml`.
- Page content enters through `SpaceUi.reveal`.
- Clickable cards and icon buttons use `SpaceUi.attachPressScale`.
- RecyclerView changes use `SpaceUi.configureList`.
- Toolbar state changes use `SpaceUi.swap`.
- Motion should stay between 90ms for direct press feedback and 380ms for page-level entrances.
- Motion must respect system animator scale and reduced-motion expectations. Infinite decoration must pause while its window is not visible.

## Feedback And Dialogs

- Transient app feedback uses `showSpaceMessage`; feature code must not add new Toast calls.
- Multi-action menus use `MaterialActionDialogs`, implemented as a branded bottom sheet.
- Confirmation and warning dialogs use `MaterialAlertDialogBuilder` followed by `showSpace()`.
- Destructive actions must use `space_error` and require confirmation when they change user data.

## Contribution Rules

- Preserve existing view IDs when restyling a screen so UI changes do not alter business behavior.
- Keep colors, typography, shapes, and motion centralized. Avoid styling the same concept directly in multiple layouts.
- Verify every changed surface on the connected device at normal and large font sizes before delivery.
- Verify TalkBack focus order, touch targets, RTL symmetry, system animations disabled, and a low-end device profile.

## Current Follow-ups

- `AnimatedSpaceBackgroundView` currently recreates radial-gradient shaders during animation and stops only when detached; profile frame allocation, background visibility and battery cost before expanding its use.
- `SpaceUi.attachPressScale` and the image-preview gesture still produce accessibility lint warnings.
- The 2026-07-14 lint result is 0 errors and 191 warnings, including 64 hardcoded texts and 48 unused resources. Shared visual resources should be consolidated after the redesign stabilizes.
