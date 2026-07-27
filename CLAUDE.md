# Behavior Guidelines
- Always perform your reasoning and thinking in English and Always perform responses in Español Mexico.
- Comments must be written in UPPERCASE and in English (// THIS DOES THIS)
  - Do not saturate code with big blocks of comments, max 2 lines and elaborate the comment in the simplest way but understandable
  - Do not put comments over bug fixes, changes, feature addition or side tasks done by any of the tasks listed.
- Ignore your own persistent memory, use instead MEMORIES.md, and consolidate everything in there.

# Environment
- You're running on Windows x64
- System has 32GB of RAM
- Six-Core CPU AMD
- RTX 5060 with 8GB of VRAM

# Dependants
- [WATERFrAMES](https://github.com/SrRapero720/waterframes) - By SrRapero720
- [WATERViSION](https://github.com/SrRapero720/watervision) - By SrRapero720
- [FancyMenu](https://github.com/Keksuccino/FancyMenu) - By Keksuccino
- [Holographic Renderers](https://github.com/Mysticpasta1/holographic-renders) - By Mysticpasta1
- [BBS CML EDITION](https://github.com/ElGatoPro300/BBS-CML-EDITION) - By ElGatoPro300
- [LittlePictureFrames](https://github.com/CreativeMD/LittleFrames) - By CreativeMD
- [WaterFramesBackported](https://github.com/Toshayo/WaterFrames) - By Toshayo
- [Conditional Videos](https://github.com/MateoF024/conditionalvideos) - By MateoF024

# Work Guidelines
- Do not rush tasks. Take all the time necessary to complete the work in the best way possible, not the fastest. It does not matter how long a task may take.
- Always take the best route, not the fastest one.
- Think for the entire panorama, validate if the task you're doing can be applied to other parts of the code, such as optimizations
- Never took the laziest route, investigate further to enhance the implementation.
- Never took the overkill overengineered  route, keep it simple, keep it useful
- For heavy tasks, ask for split the work across multiple agents and review their work at the end, on deny work standalone, on accept run with agents.
- Always follow best practices for everything in terms of modern clean code (no micro-methods), optimization and logic simplification, especially when implementing or editing code.
- Once you finish a programming-related task, go back to the original instruction, read it again, make sure you haven't forgotten anything, validate that nothing was implemented in a suboptimal or rushed way, and that everything is optimized, stable, and consolidated enough. If you find anything, fix and/or improve it, then repeat the same cycle of going back to the original instruction until everything is in good shape.
- The purpose of any task is to be the most optimized, stable, clean and simple result possible, following best practices.
- Keep the code simple and clean, without introducing redundant methods or micro methods with logic that could perfectly fit into the main method(s).
- Prefer a monolithic and centralized structure (taking advantage of JIT optimizations on variables); extract methods only when the extraction pays its cost, AND it pays when: (1) the sub-logic is genuinely reused in several places (2) Code block is bigger and complex, (3) it can be named with an abstraction the reader understands without reading its body, or (4) you need to test it in isolation. If none of that applies, the helper is noise.
- Never use AtomicBoolean, AtomicInteger, or any other Atomic* variable in code; always use volatile instead.
- Use short and clear naming for methods and variables when writing code, the best is record-like names (no get/set prefix).
- Whenever you find an error, analyze the reason and the context in which it arises and fix it the right way, not the fast way, and never paper over the error as if it were correct.
- Whenever examples are provided, do not limit yourself to those use cases; explore more possibilities that were not contemplated, think outside the box.
- Javadocs must be written following good writing practices, be short and in English.
- Write comments for complex tasks or ones with heavy algorithmic load, preferable 1 or 2 lines explaining the basics to understand how code works and/or why is there..
- Never add Javadocs to private or package-private methods, add simple comments.
- When a task requires or was requested to be run with agents, ask which agents should use and which version (fable-5, mythos-5, opus-5, opus-4-8, sonnet-5)
- Run requested searches by the user for specific code with agents with the latest Haiku model.
- When a task is completed, update the CHANGELOG.md
- Gradle: versions and constants go in gradle.properties, never in build.gradle.
- Gradle: do not use {} for simple variables (use $var, not ${var}); only use {} for object.field
- Gradle: use local gradle installation preferable over gradlew, gradle command is v9
- Gradle: after all the code changes are done or before commits, run the gradle task "removeSemicolonSpace"

# Changelog Guidelines
- Use Markdown
- Do not add spaces between change entries, only between versions. Gradle fetches changelog and cuts it at the first line separator.
- Use emojis
  - "📦" As the start emoji for the title
  - "✨" For new Features.
  - "⚙️" For API changes (addition, removal, behavior, breaking changes).
  - "🛠️" For General changes (renamed configs, internal behavior, general modifications).
  - "🐛" For Bug fixes.
- Changelog ordering is:
  - Version title
  - Per-API changes (MediaAPI, CodecsAPI, ...), all uses API changes emoji and are detailed for modders
  - General Changes (New codec support, new format support, new feature support)
- Sub-Changes are allowed to detail the change 
  - Bad pattern: "Renamed method getX() to x()" sub: "This was done for consistency"
  - Good pattern: "Renamed context() to vkContext" sub: "It clashes with Minecraft's engine methods, return times are different and allowed by JVM, just renamed to avoid wrong blames"

# Terminology
- Legal and contextual terminology for legal and documentations consult TERMINOLOGY.md

# Code Class Utilities
- libs\tools\src\main\java\org\watermedia\tools: General utility classes
  - DataTool: Data handling tools, byte manipulation and data conversion
  - IOTool: System information and system file handling
  - JSONTool: JSON handling and parsing using GSON
  - MPEGTool: Parses m3u8 files.
  - ThreadTool: Creation, synchronization and handling of Threads, factories and executors
  - VersionTool: Utility for version control

# Git
- NEVER create new branches or switch to a different one unless the user explicitly tells you to.
- Commits must follow specific syntax:
"""
Implemented X feature using Y dependency

- It allows now to run Z, prepare N and use A
- Also, integrates I
- Made configurable
- Doesn't support F
"""
- Never append the "Co-Authored-By" unless the user explicit state it (cases where you only do commits)