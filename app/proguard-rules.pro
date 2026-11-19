# Methods called from the score viewer's JavaScript.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
