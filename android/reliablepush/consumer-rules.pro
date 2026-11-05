# Consumer ProGuard/R8 rules of the Reliable Push SDK: none needed. The library uses no reflection or
# serialization of its own classes, and its one manifest component (RetryJobService) is kept by the host's aapt rules and
# by the library's own rules (proguard-rules.pro, applied when the AAR is built).
