# Browsing artwork performance implementation plan

> Execute inline with superpowers:executing-plans. The user requested planning and implementation together.

**Goal:** Reduce discarded artwork work and search response size without changing playback quality or card content.

**Architecture:** Reuse Coil cancellation handles and loading callbacks, the existing browse/badge field sets, and the shared BlurHash decoder. Keep the current prefetch window and image sizing.

**Tech stack:** Kotlin, Java, Coil 3, Compose, Jellyfin SDK, Kotest.

**Spec:** The agreed scope in this chat: artwork prefetch cancellation, lighter search metadata, unnecessary BlurHash decoding. Exclude the legacy player.

## Constraints and review focus

- Preserve per-category search limits, special video filtering, badges, and item launching.
- Retain overlapping prefetches when the window changes; cancel discarded work and all work when the grid closes or rebuilds.
- Show BlurHash on slow loads; skip quick loads and cancel decoding after success, error, disposal, or URL change.
- Preserve BlurHash-only surfaces and the existing low-RAM exclusion.
- Preserve unrelated dependency changes; leave work uncommitted until requested.

## Task 1: Cancel obsolete prefetches

- [x] Replace the grid's URL-only set with URL-to-Coil-Disposable tracking.
- [x] Dispose requests outside the current prefetch window; keep overlapping requests.
- [x] Dispose and clear requests in grid teardown and adapter rebuild.
- [x] Check cancellation and overlap retention with a focused executable check; compile Java callers.

## Task 2: Reduce search metadata

- [x] Check card display and launch requirements against existing field sets.
- [x] Add a repository test capturing actual outgoing search requests: badge fields for video categories, browse fields for other categories, no chapters/trickplay, and unchanged video filtering and limit.
- [x] Run the failing check, then select existing fields by category in SearchRepository.
- [x] Run repository and existing search tests.

## Task 3: Skip unnecessary BlurHash work

- [x] Use Coil load state, keyed by image request, to gate Compose placeholder decoding.
- [x] Give real artwork 100 ms to arrive before decoding a placeholder; success/error cancels pending work.
- [x] Add cooperative cancellation to decoder loops if required to stop already-running decoding.
- [x] Add a focused check for cancellation and retain existing BlurHash validity/aspect tests.
- [x] Keep BlurHash-only surfaces on their existing immediate decode path.

## Verification

- [x] Run affected checks and the full app unit suite with TEMP/TMP=C:\jtmp and Gradle --parallel.
- [x] Review diff and whitespace; report missing native artifacts separately from source/test failures.
- [x] Report device verification as pending; no measured speedup claim without a device run.

## Results

- Full app unit suite: 719 tests, zero failures or errors; Java/Kotlin compilation passed.
- Final source review found no remaining correctness issues. Mixed-category search keeps channel metadata and badge fields together.
- Extended the same deferred, cancellable decoding to AsyncImageView because both image paths use the shared decoder.
- Device rendering and measured performance remain unchecked. Dependency edits were preserved.
