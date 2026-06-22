# kotlinx.serialization keeps generated serializers via the plugin; no extra rules
# needed for the @Serializable classes used here. Retrofit/OkHttp ship consumer rules.
-dontwarn org.slf4j.**
