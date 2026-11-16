# Guía de uso

**Idiomas:** [English](USAGE.md) · [日本語](USAGE.ja.md) · [简体中文](USAGE.zh-Hans.md) · Español · [हिन्दी](USAGE.hi.md) · [العربية](USAGE.ar.md) · [한국어](USAGE.ko.md) · [Русский](USAGE.ru.md) · [Français](USAGE.fr.md)

Esta aplicación ejecuta un **nodo completo** de Quantus Network (red principal de QTC) en un teléfono Android. Sincroniza la cadena, verifica cada bloque por sí mismo y retransmite datos a sus pares. Por defecto funciona como no validador; también puede minar, como opción desactivada por defecto (véase **Minería** más abajo). **No** tiene billetera y nunca toca frases semilla: el único secreto que puede guardar es el Inner Hash de recompensas de minería que pegues tú mismo.

> Estado: v4.2.2 publicada (2026-11-16), la versión actual: una versión de pulido: la racha de la tarjeta de miembro ahora se etiqueta como lo que es, la racha más larga; la página de Almacenamiento también muestra el espacio libre de este dispositivo; la hoja de donación gana un botón de copia, los botones de cierre de las hojas tienen nombre para los lectores de pantalla y el contenido detrás de una hoja abierta se oculta a los servicios de accesibilidad; el bloqueo de ajustes también cubre el interruptor de precio en la hoja de precio, y la página de Información del exterior dice por qué está desactivado; las filas con valor y la fila de hoy de la pantalla principal usan el diseño de fila apilable, para que los valores largos ya no aplasten sus etiquetas; la frescura del precio se juzga con el reloj monótono y los intentos fallidos se reintentan a los 15, luego 30 y luego 60 minutos; el registro diario se escribe como máximo cada 15 minutos; y el botón de reinicio del minero queda desactivado mientras dure una parada térmica (v4.2.1, también de 2026-11-16, corrigió la auditoría externa de v4.2.0). El APK firmado está disponible en [GitHub Releases](https://github.com/ogaworld369/quantus-android-mining-node/releases) con suma de verificación SHA-256. Las compilaciones de prueba anteriores las compartía directamente el mantenedor.

## Requisitos

- Android 12 o posterior, dispositivo arm64
- Espacio libre: la base de datos del nodo está podada (solo se conservan los últimos 256 bloques), por lo que se mantiene pequeña. Medido en un Pixel 9a tras una sincronización completa (2026-11-05): 1,30 GB en total (96 MB de aplicación + 1,20 GB de datos del nodo). Como guía, mantén al menos unos 3 GB libres
- Conexión a internet. Se recomienda encarecidamente Wi-Fi para la primera sincronización (ver más abajo)

Verificado hasta ahora: **Pixel 9a con GrapheneOS**. Se espera que otros dispositivos funcionen, pero no están verificados. Si lo pruebas en otro modelo, informa del resultado (funcione o no) en un Issue de GitHub: la lista de dispositivos verificados crece con esos informes.

## Instalación

1. Obtén el archivo APK en [GitHub Releases](https://github.com/ogaworld369/quantus-android-mining-node/releases) (verifica la suma SHA-256 publicada con la versión antes de instalar).
2. Abre el APK con tu aplicación de Archivos y permite la instalación desde esa fuente cuando Android lo pida.
3. Instala y abre **Quantus Node**.

## Primera ejecución y sincronización inicial

1. (Opcional) Configura primero el **nombre visible** y la opción de **telemetría** (ver Ajustes). Los cambios de configuración requieren reiniciar el nodo (Detener → Iniciar) para aplicarse.
2. Toca **Iniciar nodo**.
3. El estado mostrará **Sincronizando** y el mejor bloque subirá hacia el bloque más alto. En un Pixel 9a, la primera sincronización desde el génesis avanza a unos 50 bloques por segundo: calcula unas **2 horas**. Una vez que alcanzas la punta de la cadena, llega un bloque nuevo cada 12 segundos aproximadamente y el estado pasa a sincronizado.
4. Durante la primera sincronización, mantén el teléfono **cargando y con Wi-Fi** si puedes. Sincronizar con datos móviles funciona, pero descarga muchos datos y consume notablemente más batería (medido: alrededor de un 11 % en 30 minutos sincronizando con datos móviles). Cuando la sincronización termina, la carga continua es pequeña.

No necesitas mantener la aplicación abierta. El nodo funciona como un servicio en primer plano: sigue ejecutándose con la pantalla apagada o mientras usas otras aplicaciones, y muestra una notificación permanente mientras funciona. Si lo detienes y lo vuelves a iniciar, la sincronización continúa donde iba: la cadena descargada se conserva en el dispositivo.

## Qué muestran las pantallas

La aplicación tiene tres pestañas en la parte inferior: Inicio, Conexión y Tarjeta de miembro. A Detalles del nodo y a Ajustes se llega desde Inicio.

- **Inicio** — el mejor bloque del nodo y su estado (Detenido / Iniciando / Sincronizando / Sincronizado / Error) y el botón principal. Mientras el nodo funciona, también muestra el número de pares conectados, el tiempo de funcionamiento de esta sesión y el precio actual de QTC. Si un inicio falla, Inicio lo muestra como un error con un botón de reintento, en lugar de una detención normal; si un ajuste que cambiaste aún no ha entrado en vigor, Inicio muestra un enlace para revisarlo
- **Conexión** — una imagen viva del campo de red de tu nodo: los pares a los que está conectado y los ecos de sus propios puntos de control registrados. Muestra relaciones, no cifras para competir: no hay clasificación
- **Detalles del nodo** — mejor bloque / bloque más alto (dónde está tu nodo frente a la punta de la red que conoce), la brecha de sincronización, los pares y el tiempo de funcionamiento, y la sección **Diagnóstico**: código de salida, notas del servicio, el registro de cada intento de inicio y **Compartir registro** (ver más abajo). Mientras el nodo está detenido, esta pantalla muestra tu trayectoria acumulada en lugar de los valores de la sesión anterior
- **Tarjeta de miembro** — tu tarjeta de miembro: tu número de miembro (el ID de tu dispositivo) y tres cifras de tu registro local: la fecha de tu primer punto de control, tu número de puntos de control y tu racha más larga de días. El registro se guarda solo en este dispositivo; nunca se sube y no vale dinero: es simplemente tu propia huella de contribución, y mostrarla o no es decisión tuya
- **Ajustes** — los ajustes siguientes, organizados como un centro de páginas. La versión de la aplicación se muestra en la página **Acerca de esta app**, que también enumera las **Licencias de código abierto** de los binarios incluidos del nodo y del minero

## Ajustes

- **Aparecer en la telemetría** (desactivado por defecto): cuando está activado, el nombre de tu nodo y su estado en vivo son visibles públicamente en la telemetría oficial en telemetry.quantus.com. Actívalo solo si quieres aparecer en la lista. Se aplica desde el siguiente inicio del nodo (Detener → Iniciar).
- **Nombre visible** — el nombre que usa tu nodo (también se muestra en la telemetría si está activada), que se edita en un diálogo. Déjalo vacío para usar el nombre por defecto `quantus-android-node`. Se aplica desde el siguiente inicio del nodo.
- **Idioma** — sigue el ajuste del dispositivo o elige uno de 9 idiomas (japonés, inglés, chino simplificado, español, hindi, árabe, coreano, ruso, francés). Se aplica de inmediato.
- **Registrar mi actividad (DePIN)** (desactivado por defecto): cuando está activado, la aplicación guarda en este dispositivo un registro firmado del tiempo de funcionamiento y del trabajo de retransmisión del nodo (un punto de control cada 10 minutos). Nada se envía solo a ninguna parte; desde Ajustes puedes exportar el registro como un archivo de paquete de prueba y compartirlo tú mismo. Al desactivarlo el registro se detiene; lo ya registrado permanece en el dispositivo
- **Bloquear ajustes** — bloquea los ajustes anteriores (telemetría, nombre visible, idioma y registro de actividad) y los ajustes de minería (el interruptor de minería y el Inner Hash) para que no se cambien por accidente mientras el teléfono queda ejecutando el nodo. Es solo una protección contra toques involuntarios: no hay PIN, y el inicio y la detención del nodo nunca se bloquean. Desactiva **Bloquear ajustes** cuando quieras volver a cambiarlos.
- **Minería** (desactivada por defecto) — el nodo también puede minar en este dispositivo, con el minero oficial incluido. Primero necesita un Inner Hash de recompensas; véase **Minería (opcional)** más abajo.

Los ajustes nunca reinician el nodo por sí solos. Si cambias la telemetría o el nombre visible con el nodo en marcha, el elemento cambiado se marca en Ajustes como aún no aplicado, e Inicio muestra un enlace para revisar los ajustes pendientes; el cambio entra en vigor en el siguiente inicio del nodo (Detener → Iniciar).

**Compartir registro** está en **Detalles del nodo → Diagnóstico**: comparte el registro del nodo como texto, y es lo que debes adjuntar al informar de un problema. Antes de pegar un registro en un lugar público, revisa su contenido: puede incluir la dirección IP pública de tu nodo e información de los pares. El diálogo para compartir puede enmascarar el nombre visible, las direcciones de las interfaces de este dispositivo y las rutas privadas de la aplicación (activado por defecto); la dirección exterior que el nodo conoce a través de sus pares no se enmascara.

## Almacenamiento

**Ajustes → Almacenamiento** muestra cuánto espacio usa la aplicación, en seis cifras: la **base de datos de la cadena**, el resto de los **datos del nodo**, los **registros (logs)**, el **registro de actividad** (DePIN), el **total** y el **espacio libre** de este dispositivo. Mientras el nodo funciona, las cifras cambian sin parar, así que tómalas como estimaciones.

Su única acción, **Eliminar la base de datos de la cadena y resincronizar**, elimina solo la base de datos de la cadena: los datos de bloques que este dispositivo ha sincronizado hasta ahora. Se conservan la clave de red del nodo, los ajustes de minería y el Inner Hash guardado, tu identidad DePIN y su registro, la trayectoria, los registros y los ajustes de la aplicación. La acción solo funciona con el nodo detenido y no se puede deshacer: la próxima vez que inicies el nodo, la sincronización empezará desde el principio, lo que lleva tiempo y datos (unas 2 horas en un Pixel 9a).

## Precio

Inicio muestra el precio actual de QTC, que la aplicación obtiene de **CoinGecko** (api.coingecko.com) aproximadamente una vez por hora. La obtención está **activada por defecto**; el interruptor está en la hoja de precio (toca el precio en Inicio) y en **Ajustes → Información del exterior**. Una obtención envía solo la consulta del precio —nunca se envía información del nodo, número de miembro ni registros—, pero CoinGecko puede ver la dirección IP de este dispositivo, como cualquier servidor al que te conectas. Con el interruptor desactivado no se obtiene nada: la fila del precio muestra «Desactivado» y la hoja muestra un guion — aunque antes se haya obtenido un precio.

## Minería (opcional)

La aplicación también puede minar, en este teléfono, con el minero oficial incluido en ella. La minería está **desactivada por defecto**, y el nodo funciona exactamente igual con ella desactivada. Al activarla, el nodo se inicia como validador y las recompensas son para ti, no para la aplicación.

**Antes de poder activar la minería, necesitas un Inner Hash de recompensas.** Las recompensas no se pagan a una dirección ordinaria: el protocolo las paga a una **dirección wormhole** derivada de un secreto llamado Inner Hash.

Para obtener un Inner Hash:

- En un PC, ejecuta el comando del nodo oficial `quantus-node key quantus --scheme wormhole`. Muestra tres valores: la **Address** (a dónde irán las recompensas), el **Inner Hash** (el valor que necesita esta aplicación) y una **Secret phrase** (tu copia de seguridad: guárdala en un lugar seguro, sin conexión).
- Si ya usas la billetera oficial, puedes derivar los mismos valores desde su frase semilla: añade `--words` al mismo comando e introduce la frase cuando se te pida — en el PC, nunca en esta aplicación. Con la misma frase semilla, las recompensas aparecen automáticamente en esa billetera.

Después, en la aplicación:

1. Abre **Ajustes → Minería** y pega el valor del **Inner Hash** (tiene la forma de `0x` seguido de 64 dígitos hexadecimales). Introduce solo el Inner Hash — nunca una frase semilla ni una clave secreta: el Inner Hash se escribe en cada bloque que produce este dispositivo y se hace público.
2. La aplicación muestra la dirección de recompensas que el nodo informa para tu Inner Hash. Compárala con la **Address** de la herramienta antes de confiar en ella.
3. Activa la minería y reinicia el nodo (Detener → Iniciar). Los ajustes de minería se aplican desde el siguiente inicio del nodo.

**Comprueba el Inner Hash con cuidado antes de guardar.** Si es incorrecto, las recompensas se pagarán a ese destino erróneo y no podrán recuperarse. El valor guardado permanece solo en este dispositivo; la aplicación nunca lo muestra en pantalla, en registros ni en textos compartidos.

**Trabajadores y calor.** Puedes elegir 1, 2, 4 o automático trabajadores de CPU (el valor por defecto es 2; siempre se deja un núcleo libre para el propio nodo). Más trabajadores significan más calor y más consumo de batería. Si el teléfono se calienta, la minería se reduce automáticamente en el nivel de calor «moderado» de la plataforma y se detiene durante el resto de la sesión en «severo»: el teléfono nunca se lleva al límite.

**Expectativas honestas:** con la tasa de hash de un teléfono, la espera esperada hasta encontrar un bloque es muy larga, mucho más que cualquier sesión de prueba. Minar aquí es una forma de participar, no de ganar dinero.

## Pasar a un dispositivo nuevo

Tu identidad DePIN y su registro pueden pasar a un teléfono nuevo. Abre **Ajustes → Transferencia entre dispositivos** en ambos dispositivos; la forma sencilla necesita un solo archivo:

1. En el **dispositivo antiguo**, toca **Crear un archivo de traspaso sencillo**. La aplicación crea el archivo y muestra un **código de transferencia** una sola vez: anótalo. En este momento, el registro en el dispositivo antiguo se detiene.
2. Lleva el archivo al **dispositivo nuevo** (como prefieras). Allí, toca **Importar un paquete de transferencia**, elige el archivo e introduce el código de transferencia.
3. El dispositivo nuevo verifica el paquete y muestra la identidad que va a asumir: el ID del dispositivo, el número de puntos de control y las fechas del primer y el último registro. Comprueba los valores y luego confirma con **Confirmar con traspaso sencillo**. La identidad ya es propia de este dispositivo, y el registro puede empezar aquí.
4. El dispositivo nuevo muestra entonces un **número de confirmación** de 8 dígitos. En el dispositivo antiguo, toca **Ingrese el número de confirmación: borrar la clave de este dispositivo** e introdúcelo: la clave antigua solo se borra cuando el número coincide. Después, borra también el archivo de traspaso donde aún quede.

Hasta que se complete el paso 4, existen dos copias de la identidad. **Trata el archivo de traspaso como una clave**: contiene la semilla de la identidad cifrada con el código de transferencia, así que quien tenga el archivo y el código puede asumir la identidad y todo su historial. Mantén ambos separados en lo posible y no publiques ninguno en un lugar público. Si el archivo nunca llegó al dispositivo nuevo, **Cancelar el traspaso sencillo** en el dispositivo antiguo permite reanudar allí el registro; pero no canceles nunca una vez que el dispositivo nuevo lo haya importado.

En **Otras acciones**, la misma página ofrece también la transferencia estricta paso a paso: en lugar del único número de confirmación, los dos dispositivos intercambian un aviso de preparación, un permiso de confirmación, un recibo y una confirmación de finalización, un archivo cada vez.

## Uso diario

- El nodo solo se detiene cuando tocas **Detener nodo**, fuerzas la detención de la aplicación o reinicias el teléfono.
- Tras un reinicio del teléfono, abre la aplicación y toca **Iniciar nodo** de nuevo: el inicio automático tras el arranque aún no está implementado.
- En algunas versiones de Android de ciertos fabricantes (Samsung, Xiaomi, etc.), el ahorro agresivo de batería puede detener los servicios en segundo plano. Si tu nodo se detiene solo, excluye esta aplicación de la optimización de batería en los ajustes del sistema.
- ¿Dejas un teléfono de repuesto enchufado las 24 horas? Revisa la batería de vez en cuando: las baterías de litio viejas pueden hincharse. Mantén el teléfono en un lugar ventilado, idealmente con un límite de carga si tu dispositivo lo ofrece.

## Si algo sale mal

- **El nodo se cierra enseguida** — abre **Detalles del nodo → Diagnóstico → Compartir registro** y envía el registro con un Issue de GitHub (plantilla de informe de error). El registro muestra cada intento de inicio y por qué terminó.
- **Los pares se quedan en 0** — revisa la conexión y luego Detener → Iniciar una vez.
- **La sincronización se atasca (tu mejor bloque se detiene mientras la red avanza)** — la aplicación detecta esta situación y reinicia el nodo por sí sola (como máximo 3 veces en 24 horas). Si se repite, ayuda una conexión Wi-Fi estable; después, Detener → Iniciar una vez
- **Tu nodo no aparece en la telemetría** — asegúrate de que la opción de telemetría está activada y de que reiniciaste el nodo después de activarla. (El listado está verificado: el nodo del propio mantenedor aparece en la lista oficial).
- **Dudas de almacenamiento** — la base de datos está podada a los últimos 256 bloques por diseño. Desinstalar la aplicación borra la base de datos y la clave de identidad del nodo, y también el Inner Hash guardado y tu identidad DePIN con todo su registro. Una reinstalación empieza una sincronización nueva con una identidad nueva, así que, si el registro debe sobrevivir, pásalo primero al dispositivo nuevo (véase **Pasar a un dispositivo nuevo** arriba).

Preguntas, informes de dispositivos e ideas son bienvenidos como Issues de GitHub. Los problemas de seguridad siguen [SECURITY.md](../SECURITY.md) en lugar de un Issue público.
