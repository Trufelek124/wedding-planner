# What the release build must keep or may ignore, beyond what the libraries say for themselves.
# Each rule says why it is here; a rule nobody can explain is a rule to remove.

# Names stay as they are written. The source is public, so renaming hides nothing, and a stack
# trace that comes from a phone is to be read as it is, without a mapping file to look names up in.
-dontobfuscate
