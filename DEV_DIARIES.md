# CAPRICA, NO MUCH IDEA OF WHAT VLC DOES
After all the years I was working with VLCJ on WaterMedia, I was way more convinced
that caprica (creator of VLCJ) has no much idea of how VLC works or how to translate the
VLC behavior into Java.

The whole VLCJ API is a redundant mess, he tried to "make safe" the Java side
without asking stuff to natives. Separating stuff in API layers, making more
confusing understand _why a random JNA callback that you didn't never added got garbage collected_.

One of my first priorities on V2 was expand support for streaming platforms without
give it a proper look to VLCJ, On the nowday, I find that the _start_ method of VLCJ
was (on purpose) converted into a blocking method, which is a bad idea
and wasn't even documented.

Not even that, libVLC events in VLCJ got added and removed like a pair or pants,
without considering G1GC, which always clears things that are not
referenced _in a java context_, ignoring the native context, basically running 
a _buckshot roulette_ like for a race condition on the native side when the MediaPlayer got
cleared.

No much to say, I am not a fan of VLCJ, and I am glad that I can finally get rid of it.
I really want to contribute to the VLCJ project to fix the issues mentioned above, 
but I that might require a lot of work, and i am pretty sure that caprica is not
interested in that, or even have time to give it a shot.

# ANXIETY AS DEV
My journey performing the v3 update was a rollercoaster, first my motivation was high.
With a lot of ideas, way to implement them and stuff, now I am in a state of anxiety.

WaterMedia `main` (v3) branch was a chaos, v2 code with v3 code, API layers done before
the critical implementation, etc.

So, I decided to start from scratch, a new branch, a new codebase, a new API layer, this
time with a new focus, start with the core (players, decoders, tests) and then the API
layer and the bootstrap.

I started now just the VLC player, finally ridding off VLCJ and rely on natives.
For the first time, I can see purpose and order on what i am coding. And it feels great.