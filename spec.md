# WaxWatch Specification

## Core Objective
WaxWatch is a specialized Android extension for the Hammerhead Karoo cycling computer. Its primary purpose is to **track and predict the remaining lifespan of chain wax** across multiple user-defined Activity Profiles. It allows cyclists to optimize their rewaxing schedule based on rider-specific physics, equipment choices, and environmental conditions.

---

## Technical Architecture

### Architectural Pattern
- **Component-Based Extension**: The app follows a functional separation between the **UI Layer** (Jetpack Compose) and the **System Extension Layer** (Karoo SDK Extension Service).
- **Data Repository Pattern**: Centralized state management through `WaxRepository`, which handles persistence and inter-process synchronization.
- **Background Service**: Operates as a background extension service that survives UI closure, ensuring continuous distance tracking during rides.

### Frameworks & Libraries
- **Language**: Kotlin 1.9
- **UI**: Jetpack Compose (Material 3)
- **SDK**: `io.hammerhead.karooext:karoo-ext` (Karoo Extension SDK)
- **Concurrency**: Kotlin Coroutines & Flow (for real-time data streaming)
- **Persistence**: `SharedPreferences` (JSON-serialized models) with `MODE_MULTI_PROCESS` for cross-process (App/Extension) data consistency.

---

## Feature Breakdown

### 1. Dynamic Wax Life Calculation
- **Weight-Based Scaling**: Adjusts the manufacturer's baseline distance based on the rider's weight. Uses a 75kg benchmark with a 1.5% adjustment per kg (symmetric: more life for lighter riders, less for heavier).
- **Surface Multipliers**: Applies wear-rate penalties based on terrain:
  - **Road (Pavement)**: 1.0x (Baseline)
  - **Mixed/Commute**: 1.2x wear
  - **Gravel/Dirt**: 1.5x wear

### 2. Automatic Profile Discovery
- **ZERO Configuration**: Profiles are not created manually. The extension automatically detects and registers new Karoo Activity Profiles the moment a ride is started with them.
- **Independent Tracking**: Each profile maintains its own independent `WaxState` (Max Life, Remaining, Surface Type).

### 3. Background Distance Tracking
- **System Stream Integration**: Hooks into the Karoo's native `DISTANCE` data stream.
- **Auto-Update**: Calculates and decrements wax life in real-time based on distance delta and the active profile's multiplier.
- **State Persistence**: Flushes state to disk every 10 seconds or upon ride status changes.

### 4. Interactive data fields
- Provides two native Karoo data fields:
  - **Wax Life %**: Visual percentage of remaining life.
  - **Wax Rem. Dist**: Localized distance (km/mi) remaining until rewaxing is required.

### 5. Smart Alerts & Safety
- **Low-Life Threshold**: User-configurable alert percentage (default 20%).
- **Push Notifications**: Sends a native Karoo notification when the threshold is breached during a ride.
- **The "Rain Button"**: A one-touch physical penalty that deducts 30% of max life to account for the immediate wax degradation caused by wet conditions.

---

## Data Models

### WaxState
The primary unit of persistence for a profile.
- `profileId`: String (Unique identifier from Karoo System)
- `remainingDistanceMeters`: Double
- `maxLifeMeters`: Double
- `surfaceType`: Enum (PAVEMENT, MIXED, GRAVEL)
- `alertTriggered`: Boolean (Prevents notification spam)

### WaxType
Enum defining baseline laboratory lifespans for representative products:
- *Examples*: Generic Paraffin (350km), Silca Secret (800km), CeramicSpeed UFO (600km).

---

## Constraints & "Constitution"

### "The Implied Rules"
1. **Minimalist Library Footprint**: Avoids heavy database frameworks (Room/SQL) in favor of lightweight JSON/SharedPreferences to minimize APK size and memory impact on the head unit.
2. **Defensive Distance Math**: All distance calculations include guards (e.g., `coerceIn(0, 100)`) to prevent negative life or >100% values from sensor jitter.
3. **Local Locale Persistence**: Unit systems (Kilometers vs Miles) are derived from the system locale but support manual overrides to prevent "locked" localized logic.
4. **Inter-Process Priority**: Since the Extension Service and Main App run in separate processes, `SharedPreferences` MUST use `MODE_MULTI_PROCESS` and explicit commits to ensure the background tracker never uses stale configuration data.
5. **No Sugar in Logic**: Calculation logic (e.g., `WaxCalculator`) is kept in pure Kotlin `object` form, independent of Android dependencies, facilitating easier testing and clarity of "physics".
