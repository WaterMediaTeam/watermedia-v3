# Language
- Always reason and think in English, but always reply to the user in Spanish.
- Comments must be written in UPPERCASE and in English (// THIS DOES THIS)

# Work Guidelines
- Do not rush tasks. Take all the time necessary to complete the work in the best way possible, not the fastest. It does not matter how long a task may take.
- Always take the best route, not the fastest one.
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
- Gradle: versions and constants go in gradle.properties, never in build.gradle.
- Gradle: do not use {} for simple variables (use $var, not ${var}); only use {} for object.field
- Gradle: use local gradle installation preferable over gradlew, gradle command is v9

# Terminology
- Legal and contextual terminology for legal and documentations consult TERMINOLOGY.md

# Utilities
- libs\tools\src\main\java\org\watermedia\tools: General utility classes
  - DataTool: Data handling tools, byte manipulation and data conversion
  - IOTool: System information and system file handling
  - JsonTool: JSON handling and parsing using GSON
  - ThreadTool: Creation, synchronization and handling of Threads, factories and executors
  - VersionTool: Utility for version control

# Git
- NEVER create new branches or switch to a different one unless the user explicitly tells you to.