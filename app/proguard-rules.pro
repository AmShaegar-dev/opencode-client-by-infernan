# JSch работает через reflection в некоторых местах.
-keep class com.jcraft.jsch.** { *; }
-dontwarn com.jcraft.jsch.**
