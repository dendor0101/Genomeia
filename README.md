# Genomeia

A simulation game about evolution and creating living organisms, where you design creatures with unique behaviors and watch them interact, survive, and evolve.

Written in Kotlin using [libGDX](https://libgdx.com/) game framework.

## 📱 Social Media

- **[TikTok](https://www.tiktok.com/@genomeia)** - Short videos of creature behavior
- **[Telegram (RU)](https://t.me/genomeia)** - Russian community channel
- **[Discord (EN)](https://discord.com/invite/HRajjtbENs)** - English community server
- **[YouTube](https://www.youtube.com/@Genomeia-project)** - Gameplay videos and tutorials

---

## 🎮 Game Overview

**Genomeia** is a biological simulation game where players:
- Design custom organisms by arranging different cell types
- Program neural networks to control creature behavior
- Watch creatures evolve through natural selection
- Create ecosystems with multiple species competing for resources

### Core Concepts

1. **Cells as Building Blocks**: Every organism is built from different cell types, each with unique functions
2. **Neural Networks**: Connect cells with neural links to create complex behaviors
3. **Genome System**: Creatures reproduce based on genetic instructions encoded in their genome
4. **Physics Simulation**: Realistic physics govern movement, collisions, and energy transfer
5. **Evolution**: Mutations and natural selection drive the evolution of your creatures

---

## 🧬 Cell Types

The game features various specialized cell types that form the building blocks of all organisms:

### Basic Cells

| Cell Type | Function | Description |
|-----------|----------|-------------|
| **Zygote** | Starter Cell | The initial cell that begins every organism. Absorbs solar energy and initiates organ development |
| **Leaf** | Energy Producer | Photosynthesizes to generate energy from sunlight. Essential for autotrophic organisms |
| **Producer** | Reproducer | Creates new zygotes when enough energy is accumulated. Controlled by neural impulses |

### Structural Cells

| Cell Type | Function | Description |
|-----------|----------|-------------|
| **Bone** | Structure | Provides rigid structural support. Does not consume energy |
| **Muscle** | Movement | Contracts/expands based on neural signals, enabling movement |
| **Tail** | Propulsion | Provides thrust for swimming and movement |

### Neural Cells

| Cell Type | Function | Description |
|-----------|----------|-------------|
| **Neuron** | Signal Processing | Processes and transmits neural impulses through the organism |
| **Eye** | Vision | Detects colors and objects in line of sight. Outputs neural signals based on visual input |
| **Sensor** | Environmental Detection | Senses environmental conditions and converts them to neural signals |
| **PheromoneSensor** | Chemical Detection | Detects pheromone trails left by other organisms |

### Special Cells

| Cell Type | Function | Description |
|-----------|----------|-------------|
| **Punisher** | Attack | Destroys cells from other organisms on contact |
| **Sticky** | Adhesion | Allows organisms to stick to surfaces or other organisms |
| **Sucker** | Feeding | Extracts energy from other organisms or surfaces |
| **Chameleon** | Camouflage | Changes color to blend with surroundings |
| **Compass** | Direction | Provides directional orientation |
| **TouchTrigger** | Contact Response | Triggers actions upon physical contact |
| **Vascular** | Transport | Transports nutrients and energy between cells |
| **Breakaway** | Defense | Can detach from the main organism |
| **Excreta** | Waste | Produces waste materials |
| **Fat** | Storage | Stores excess energy for later use |
| **Mike** | Unknown | Special function cell |
| **SuctionCup** | Attachment | Provides strong attachment to surfaces |
| **Pumper** | Circulation | Pumps fluids through vascular system |

---

## 🏗️ Architecture

### Project Structure

```
Genomeia/
├── core/                    # Main game logic (shared across platforms)
│   └── src/main/java/io/github/some_example_name/old/
│       ├── cells/          # Cell type implementations
│       ├── entities/       # ECS entity definitions
│       ├── systems/        # Game systems (physics, rendering, simulation)
│       ├── editor/         # Genome editor functionality
│       ├── ui/             # User interface screens
│       └── core/           # Core utilities and dependency injection
├── android/                # Android platform launcher
├── lwjgl3/                 # Desktop (PC/Mac/Linux) launcher
├── ios/                    # iOS platform launcher
├── assets/                 # Game resources
│   ├── shaders/           # GPU shader programs
│   ├── genomes/           # Pre-built creature genomes
│   ├── fonts/             | Font files
│   └── ui/                | UI textures and localization
└── gradle/                # Build configuration
```

### Technology Stack

- **Language**: Kotlin 2.1.0
- **Game Framework**: libGDX 1.13.1
- **UI Library**: VisUI 1.5.3
- **Serialization**: Kryo 5.5.0
- **Collections**: fastutil 8.5.16
- **Build Tool**: Gradle
- **JVM Target**: Java 8

### Core Systems

#### 1. Simulation System (`SimulationSystem.kt`)
Main game loop manager that coordinates all subsystems:
- Runs multi-threaded simulation updates
- Manages tick-based game progression
- Coordinates physics, cell behavior, and rendering

#### 2. Cell System (`CellSystem.kt`)
Executes cell-specific logic:
- Calls `doOnTick()` for each cell
- Manages cell energy consumption
- Handles cell division and mutation

#### 3. Physics System
- **ParticlePhysicsSystem**: Handles particle collisions and movement
- **LinkPhysicsSystem**: Manages connections between cells (muscles, bones)
- **GridManager**: Spatial partitioning for efficient collision detection

#### 4. Genome System
- **GenomeManager**: Loads and manages creature genomes
- **DivideManager**: Handles cell division based on genome instructions
- **MutateManager**: Applies mutations during reproduction
- **OrganManager**: Tracks organism development stages

#### 5. Rendering System
- **RenderSystem**: Main rendering pipeline
- **ShaderManager**: GPU shader management
- **RenderBufferManager**: Double-buffered rendering for smooth visualization

#### 6. Editor System
- **EditorLogicSystem**: Genome editing logic
- **EditorRenderSystem**: Visual feedback in editor
- **Command System**: Undo/redo support for editing operations

### Entity Component System (ECS)

The game uses a custom ECS architecture:

**Entities:**
- `CellEntity` - Cell state (position, energy, type, etc.)
- `LinkEntity` - Connections between cells
- `ParticleEntity` - Physical particles
- `OrganEntity` - Organism organization
- `NeuralEntity` - Neural network data
- `SubstancesEntity` - Chemical substances
- `PheromoneEntity` - Pheromone trails
- `SpecialEntity` - Special cell data

---

## 🔧 Genome System

### Genome Structure

A genome defines how an organism develops and behaves:

```kotlin
class Genome(
    val name: String,
    val genomeStageInstruction: MutableList<GenomeStage>,
    val dividedTimes: IntArray,
    val mutatedTimes: IntArray
)
```

**GenomeStage**: Represents a developmental stage containing cell actions

**CellAction**: Defines what a cell can do:
- **Divide**: Split into two cells with specific properties
- **Mutate**: Change cell type or properties

**Action Properties:**
- `angle`: Division angle
- `cellType`: Type of daughter cell
- `physicalLink`: Connection data (length, neural properties)
- `color`: Cell color
- `activation function`: Neural activation parameters

### Genome File Format

Genomes are stored as JSON files in `assets/genomes/`. Example pre-built genomes:
- `Fish.json` - Swimming creature
- `Caterpillar.json` - Crawling organism
- `Megalodon.json` - Large predator
- `Tardigrade.json` - Resilient microorganism
- `Star.json` - Radially symmetric creature

---

## ⚙️ Technical Details

### Multi-threading

The simulation runs on multiple threads for performance:
- Configurable thread count via `threadCount` setting
- Chunk-based parallel processing for physics and cell updates
- Thread-safe command buffers for world modifications

### Physics

- **Grid-based spatial partitioning**: Efficient collision detection
- **Verlet integration**: Stable physics simulation
- **Hydrodynamic drag**: Realistic water resistance (optional)
- **Link constraints**: Maintains connections between cells

### Neural Network

- **Impulse-based signaling**: Analog neural signals (-1 to 1)
- **Activation functions**: Configurable per neuron
- **Synaptic weights**: Adjustable connection strengths
- **Signal propagation**: Tick-based impulse transmission

### Rendering

- **Custom shaders**: GPU-accelerated rendering
- **MSAA support**: Anti-aliasing options (1x, 2x, 4x)
- **Post-processing**: Blur and other effects
- **Layered rendering**: Separate passes for cells, links, particles

---

## 🛠️ Building & Running

### Prerequisites

- **JDK 8 or higher**
- **Android Studio** (for Android development)
- **Gradle** (included via wrapper)

### Build Commands

**Desktop (LWJGL3):**
```bash
./gradlew lwjgl3:run
```

**Android:**
```bash
./gradlew android:installDebug
```

**iOS:**
```bash
./gradlew ios:launchIOSDevice
```

### Build Configuration

Edit `gradle.properties` to configure:
- `kotlinVersion`: Kotlin compiler version
- `gdxVersion`: libGDX version
- `enableGraalNative`: Enable GraalVM native compilation
- `android.useAndroidX`: AndroidX support

---

## 📁 Assets

### Shaders

Located in `assets/shaders/`:
- **cells/**: Cell rendering shaders
- **grid/**: Grid visualization
- **post_process/**: Screen effects
- **blur/**: Blur effects
- **debug/**: Debug visualization

### Localization

Multi-language support in `assets/ui/i18n/`:
- `MyBundle.properties` - English (default)
- `MyBundle_ru.properties` - Russian
- `MyBundle_es.properties` - Spanish

### Fonts

Roboto font with Cyrillic character support for international text rendering.

### Music & Sounds

- 5 background music tracks (OGG format)
- 5 sound effects (MP3 format) for UI interactions

---

## 🎯 Game Modes

### 1. Simulation Mode
Run simulations with selected genomes in generated or custom worlds.

**Controls:**
- **Pause/Resume**: Control simulation speed
- **Speed Up**: Accelerate time
- **Restart**: Reset simulation
- **Spawn Organism**: Place creatures manually

### 2. Genome Editor
Design custom organisms with visual editing tools.

**Features:**
- **Cell Placement**: Add/remove cells
- **Neural Linking**: Connect cells with neurons
- **Division Programming**: Set division rules
- **Mutation Settings**: Configure mutation rates
- **Save/Load**: Export/import genomes as JSON
- **Test**: Immediately test edited genomes

### 3. World Editor
Create custom environments.

**Tools:**
- **Brush Tools**: Paint terrain features
- **Seed Control**: Set random generation seed
- **Day/Night Cycle**: Configure lighting
- **Starting Position**: Set organism spawn point

---

## ⚙️ Settings

### Graphics
- **UI Scale**: Adjust interface size
- **MSAA**: Anti-aliasing quality (important for weak devices)
- **Draw Links**: Toggle link visualization
- **Draw Rays**: Show vision rays from eyes

### Audio
- **Music Volume**: Background music level
- **Sound Volume**: Effects volume

### Simulation
- **Safe Division Mode**: Prevent problematic divisions
- **Hydrodynamic Drag**: More accurate water physics
- **Show Physical Links**: Display connection forces

---

## 🤝 Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines.

### Code Style
- Follow Kotlin coding conventions
- Use meaningful variable names
- Document public APIs
- Write unit tests for new features

### Architecture Principles
- Maintain separation of concerns
- Use dependency injection via `DI` containers
- Keep systems modular and testable
- Prefer composition over inheritance

---

## 📄 License

This project is licensed under the License - see the [LICENSE](LICENSE) file for details.

---

## 🙏 Acknowledgments

- [libGDX](https://libgdx.com/) - Cross-platform game framework
- [VisUI](https://github.com/kotcrab/vis-ui) - UI library for libGDX
- [Kryo](https://github.com/EsotericSoftware/kryo) - Serialization library
- All contributors and community members

---

## 📞 Support

For questions, suggestions, or bug reports:
- Join our [Discord server](https://discord.com/invite/HRajjtbENs)
- Follow us on [Telegram](https://t.me/genomeia)
- Check existing issues on GitHub

---

*Genomeia - Where life evolves by your design*
