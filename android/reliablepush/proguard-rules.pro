# Rules applied when this library's release variant is minified (R8). The public API in io.reliablepush.android
# and the manifest-declared job service must survive unchanged; everything under .internal may be renamed or removed.
-keep public class io.reliablepush.android.* {
    public protected *;
}
-keep class io.reliablepush.android.internal.RetryJobService { *; }
# Internal classes are moved into one package so they cannot collide with other pre-minified libraries (a.a, b.a ...)
-repackageclasses io.reliablepush.android.internal
-keepattributes Signature,InnerClasses,EnclosingMethod,RuntimeVisibleAnnotations,*Annotation*
-keep class kotlin.Metadata { *; }
