# What the release build must keep or may ignore, beyond what the libraries say for themselves.
# Each rule says why it is here; a rule nobody can explain is a rule to remove.

# Names stay as they are written. The source is public, so renaming hides nothing, and a stack
# trace that comes from a phone is to be read as it is, without a mapping file to look names up in.
-dontobfuscate

# The widget's class is found by its name. Glance writes the name of a widget's class into the phone's preferences, to know
# which widgets on the home screen are the planner's, and asks for the widgets by that name again after the app has been
# restarted or updated. Left to the shrinker the class is merged into another one and called something else, which can differ
# from one build to the next: the widget that is already on the screen would no longer be drawn. (The receiver is named in the
# manifest, which keeps it, and Glance's own rules keep the classes that answer a press, such as TickCallback.)
-keep class io.github.trufelek124.weddingplanner.widget.PlannerWidget
