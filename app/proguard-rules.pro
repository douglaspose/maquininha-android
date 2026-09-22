# kotlinx.serialization guarda os nomes dos campos nos serializers gerados;
# sem isto o R8 renomeia e o JSON da API chega vazio em release.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class br.com.gestaorestaurante.maquininha.** {
    *** Companion;
}
-keepclasseswithmembers class br.com.gestaorestaurante.maquininha.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# A SDK da Stone faz reflexão em cima dos próprios modelos.
-keep class br.com.stone.** { *; }
-keep class stone.** { *; }
