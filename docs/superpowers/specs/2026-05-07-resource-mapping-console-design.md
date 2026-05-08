# Resource Mapping Console Design

## Context

The project already exposes `ResourceMapping` CRUD APIs for grouped lookup and item-level maintenance:

- `GET /api/v1/resource-mappings?serviceName=&clusterName=`
- `POST /api/v1/resource-mappings/items`
- `PUT /api/v1/resource-mappings/items/{id}`
- `DELETE /api/v1/resource-mappings/items/{id}`

There is currently no frontend in the repository. The fastest and lowest-risk delivery path is to add a static admin page served by Spring Boot instead of introducing a separate frontend build system.

## Goals

- Provide a usable internal console for querying, creating, editing, and deleting resource mapping items.
- Keep the implementation self-contained inside the current Spring Boot app.
- Favor operational efficiency over heavy frontend architecture.
- Add enough visual polish that the page feels intentional rather than purely utilitarian.

## Non-Goals

- No authentication or role model changes.
- No pagination, batch edit, or batch delete in this iteration.
- No new backend APIs unless the existing CRUD contract proves insufficient.
- No frontend framework, router, or component library in this iteration.

## Chosen Approach

Implement a single static page under Spring Boot static resources using vanilla HTML, CSS, and JavaScript.

Why this approach:

- It matches the current repository shape, which has no existing frontend stack.
- It keeps deployment and local startup unchanged.
- The page only needs one screen and a small amount of state, so framework overhead would outweigh the benefit.

## User Experience

### Layout

Use a two-column management layout:

- Left column: filter panel and grouped result list
- Right column: selected group summary, item table, feedback area, and primary action button

The visual direction should be a light operations console with warm neutrals, a slightly editorial typographic feel, and clear contrast between navigation, data, and actions.

### Interaction Model

- Search happens from the left-side filter panel.
- Results are shown as grouped cards keyed by `serviceName + clusterName`.
- Clicking a group loads that group into the detail pane on the right.
- Creating a new item opens a modal form.
- Editing an existing item opens the same modal prefilled with the selected row.
- Deleting an item happens from the row action area and requires confirmation.

### Empty and Transitional States

- If no filters are entered, the page loads all groups.
- If the query returns no groups, show an empty result card with guidance.
- If no group is selected yet, auto-select the first returned group.
- If the selected group becomes empty after deletion, remove it from the list and select the next available group.

## Information Architecture

### Left Column

- Page title and short helper text
- Filter inputs:
  - `serviceName`
  - `clusterName`
- Actions:
  - `Search`
  - `Reset`
- Group list:
  - service name
  - cluster name
  - resource count
  - selected state styling

### Right Column

- Selected group title
- Summary chips:
  - service name
  - cluster name
  - item count
- Primary CTA: `Create Item`
- Resource table:
  - `id`
  - `resourceType`
  - `resourceId`
  - `resourceName`
  - `metadataJson` preview
  - actions
- Feedback area for loading, success, and error messages

### Modal Form

Fields:

- `serviceName`
- `clusterName`
- `resourceType`
- `resourceId`
- `resourceName`
- `metadataJson`

Behavior:

- Shared between create and edit modes
- `resourceType` offered as a select with `DATABASE`, `DCS`, `MQ`
- `metadataJson` uses a textarea with a starter JSON placeholder
- Client-side trim and required checks before submission
- Preserve server validation messages in the UI when the API rejects the request

## Technical Design

### File Placement

Add static page assets under Spring Boot static resources:

- `src/main/resources/static/resource-mappings/index.html`
- `src/main/resources/static/resource-mappings/styles.css`
- `src/main/resources/static/resource-mappings/app.js`

### Data Flow

1. Initial page load requests `GET /api/v1/resource-mappings`.
2. Response groups populate the left list and the current detail pane.
3. Search submits the two optional query parameters.
4. Create submits `POST /api/v1/resource-mappings/items`.
5. Edit submits `PUT /api/v1/resource-mappings/items/{id}`.
6. Delete submits `DELETE /api/v1/resource-mappings/items/{id}`.
7. After any successful mutation, refresh the grouped list from the server and restore selection where possible.

### Client State

Minimal in-memory state is sufficient:

- `filters`
- `groups`
- `selectedGroupKey`
- `modalMode`
- `editingItemId`
- `loadingState`
- `flashMessage`

No persistent client storage is required.

### Rendering Strategy

- Keep HTML mostly structural.
- Render dynamic group cards and table rows from JavaScript.
- Use small helper functions for:
  - API requests
  - state updates
  - modal open/close
  - form serialization
  - message rendering

## Error Handling

- Network failure: show a prominent retryable error in the feedback area.
- Validation failure from backend: show the returned message inside the modal and keep user input intact.
- Duplicate key conflict: surface the exact backend message so the operator knows which key combination already exists.
- Delete failure: keep the table unchanged and show an inline error.
- Invalid JSON in `metadataJson`: perform a client-side parse check before submit, but still defer to backend as source of truth.

## Accessibility and Usability

- Use semantic form controls and table markup.
- Modal must support keyboard close via `Escape`.
- Primary interactive elements must have visible focus states.
- Keep button labels explicit instead of icon-only.
- Avoid dense all-caps admin styling; keep content readable for repeated daily use.

## Testing Strategy

### Frontend Behavior

Because the page is static and self-contained, test coverage should focus on:

- initial load rendering
- filter submission and reset behavior
- group selection behavior
- modal create flow
- modal edit flow
- delete confirmation flow
- error rendering

### Project-Level Verification

Run targeted backend tests to confirm the page still integrates with the existing controller contract:

- `ResourceMappingControllerTests`
- `ResourceMappingControllerIntegrationTests`

If frontend tests are added in this iteration, prefer light DOM-level tests only if the existing build already supports them. Otherwise, manual verification in the browser is acceptable for the first slice.

## Implementation Notes

- Reuse the existing API response envelope and unwrap `data` on the client.
- Do not infer fields the backend does not provide.
- Keep styling local to the page so we do not accidentally affect future static pages.
- Favor clear, maintainable DOM code over clever abstractions.

## Risks and Mitigations

### No Existing Frontend Stack

Risk:

Vanilla JavaScript can become messy if structure is not deliberate.

Mitigation:

Keep the page modular by splitting structure, style, and behavior into separate files and using small rendering helpers.

### `metadataJson` Readability

Risk:

Raw JSON is harder to scan in a table.

Mitigation:

Show a shortened preview in the table and the full editable content only in the modal.

### Refresh After Mutation

Risk:

Blind full refreshes can make the UI feel jumpy.

Mitigation:

Refresh from the server for correctness, then restore the previously selected group when it still exists.

## Deliverable

The first implementation slice should produce one functional browser-accessible page at a static Spring Boot path that supports:

- listing grouped mappings
- filtering by service and cluster
- creating an item
- editing an item
- deleting an item
- showing success and failure feedback clearly
