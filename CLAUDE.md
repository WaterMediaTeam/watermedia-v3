# Lineamientos de Trabajo
- No apresures las tareas. Toma todo el tiempo necesario para completar el trabajo de la mejor forma posible, no la mas rapida. No importa que tanto pueda tomar una tarea.
- Siempre toma la mejor ruta, no la mas rapida.
- Para tareas pesadas, preferentemente divide el trabajo en multiples agentes y revisa su trabajo al final.
- Siempre sigue las buenas practicas para todo, en especial al implementar o editar codigo.
- Una vez finalizes una tarea relacionada con programar, regresa a la instruccion original y asegurate que no hayas olvidado nada, que hayas implementado algo de forma suboptima o apresurada, o si algo no esta optimizado o es lo suficientemente estable o consolidado. Si encuentras algo, arreglalo y/o mejoralo, repite el mismo ciclo de regresar a la instruccion original hasta que todo se encuentre en buen estado.
- El proposito de cualquier tarea requiere ser la mas optimizada, estable y con un resultado limpio, siguiendo las mejores practicas.
- Manten el codigo simple y limpio, sin introducir metodos redundantes con logica que perfectamente puede encajar en el metodo o los metodos principales.
- Prefiere una estrucctura monolitica (aprovechando optimizaciones del JIT en variables), extrae métodos solo cuando la extracción paga su costo, Y paga cuando: (1) la sublógica se reutiliza de verdad en varios sitios, (2) se puede nombrar con una abstracción que el lector entiende sin ir a leer el cuerpo, o (3) necesitas probarla aislada. Si nada de eso aplica, el helper es ruido.
- Nunca utilices AtomicBoolean, AtomicInteger, o cualquier otra variable Atomic* en codigo, siempre utiliza volatile.
- Usa nomenclaturas cortas y claras en los metodos y variables cuando escribas codigo
- Siempre que encuentres un error, analiza el motivo y el contexto en el que surge y solucionalo de la manera correcta, no de la manera rapida y tampoco tapes el error como si fuera correcto.
- Los comentarios deben ser escritos en mayusculas y en idioma ingles (// THIS DOES THIS)
- Siempre que se proporcionen ejemplos, no te quedes solo con esos casos de uso, explora más posibilidades que no hayan contempladas
- Los Javadocs deben ser escritos con las buenas practicas de escritura en idioma ingles
- Escribe comentarios para tareas complejas o con mucha carga algoritmica.
- Nunca agregues javadocs a metodos private o package-private
- Gradle: las versiones y las constantes van en gradle.properties, nunca en build.gradle.
- Gradle: no uses {} en variables simples (usa $var no ${var}); solo usa {} para object.field

# Git
- NUNCA hagas nuevas branches o cambies a una diferente salvo que el usuario te lo diga explicitamente