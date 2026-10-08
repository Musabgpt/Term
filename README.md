# Arabic Terminal — Alpine Linux on Android

تطبيق مستقل لسطح أوامر Linux على Android؛ يتضمن Alpine Linux ARM64 الحقيقي مع apk package manager وبيئة ملفات Linux عبر PRoot. يعمل بدون Root أو Termux آخر؛ **ليس kernel Linux منفصلًا** ويشارك نواة Android. بناء APK يجلب Alpine minirootfs الموقّع/المتحقق بصمته من الموقع الرسمي وحزم PRoot من مستودع Termux ويضمنها داخل ملف التطبيق؛ لا يلزم الإنترنت لبدء Linux بعد التثبيت. تحتاج اتصال إنترنت لتثبيت برامج من apk.

## الميزات

- Alpine rootfs حقيقي: /etc /bin /usr وapk add وBusyBox والأدوات المتاحة، بعد فك الملفات مرة واحدة.
- Android PTY مع دعم ANSI وتقسيم الجلسات وأوامر عربية: «افتح واتساب» «الرئيسية» «ارجع» «اعرض التطبيقات» وغيرها.
- خيار Root Android عندما يتوفر su ويمنح المستخدم الإذن. الجذر الوهمي داخل PRoot ليس صلاحيات جذر Android.
- واجهة طرفية مبسطة وتثبيت موضع الإدخال فوق لوحة مفاتيح الهاتف.
- ARM64 فقط في الإصدار الحالي، محاكاة PRoot لا تدعم systemd كجهاز لينكس مستقل، وبعض أوامر النواة قد لا تعمل.

## البناء

GitHub Actions → بناء الطرفية العربية APK → Artifact الخاص بالبناء الناجح. ملفات Linux تُدرج خلال بناء GitHub. تتطلب Java 17 وAndroid SDK 36 وNDK 27 وGradle 8.13. التجربة على جهاز Samsung فعلي مطلوبة للتحقق من سياسة ptrace وSELinux ومن أداء PRoot.

## الاعتمادات والتراخيص

الواجهة الخاصة بنا Apache-2.0. PRoot وتبعياته لها تراخيص GPL/LGPL خاصة بها، ويجب الحفاظ على الإشعارات وتوفير المصادر عند إعادة توزيعها: https://github.com/termux/termux-packages/tree/master/packages/proot ، https://github.com/proot-me/proot ، https://github.com/termux/libandroid-shmem . Alpine: https://alpinelinux.org/about/ . Apache Commons Compress: Apache-2.0.

لا يمكن للتطبيق تجاوز قيود Android أو الحصول على Root بمجرد قبول إذن إمكانية الوصول. التحكم المتقدم يخضع للصلاحيات الفعلية في النظام.

## تشخيص الشبكة

نفذ `فحص الشبكة` لاختبار HTTPS من Android، ثم `فحص لينكس` لاختبار DNS و`curl` وإمكانية الكتابة و`apk update` داخل Alpine نفسها. ظهور HTTP 200 في اختبار Android لا يثبت نجاح Linux/PRoot networking. لا تعطل فحص TLS ولا تستخدم `apk --allow-untrusted` لتجاوز أخطاء الاتصال.

اختبار أدوات Alpine المدمجة على سطر واحد: `python3 --version; git --version; node --version; npm --version`. استخدام مسافات بين الأوامر ليس مكافئًا للفواصل المنقوطة.

## تجربة ترمينال كمبيوتر أقرب للينكس الكامل

الإصدار 0.7 يتضمن Bash كصدفة افتراضية، وأدوات Linux إضافية مثل coreutils وnano وtmux وhtop وfindutils وripgrep وjq وopenssh-client وzip/unzip، بجانب Python/Git/Node/npm. هذه الأدوات مضمّنة من وقت البناء ولا تحتاج apk update عند أول تشغيل؛ وبعض برامج TUI قد تحتاج مزيداً من توافق شاشة ANSI على الهاتف.

لحماية بيانات النسخة السابقة، الإصدار 0.7 يستخدم معرّف تطبيق منفصلاً بحيث يثبت بجانب نسخة 0.6؛ ملفات النسخة القديمة لا تنتقل تلقائياً بسبب عزل Android.


## 0.8 — ثابت متماسك مع أرشيف Alpine
النسخ السابقة تعثرت عند zipinfo -> unzip بسبب إنشاء Hard Link على Android. الإصلاح مزدوج: GNU tar --hard-dereference يولد ملفات مستقلة ويتحقق من صفر روابط صلبة في الأرشيف، ومثبت Java يستخدم SafeRootfsExtractor بفحص المسارات، وتحويل روابط Hard Link إلى نسخ ملفات بدلاً من Files.createLink. اختبارات Android JVM تفك أرشيف Alpine الكامل وتتحقق من Bash/Python/Git/Node/npm قبل بناء APK.
معرّف التطبيق عاد إلى io.musab.arabicterminal بناءً على موافقة المستخدم على حذف النسخ القديمة. مفتاح Debug على GitHub قد يختلف في عمليات البناء اللاحقة، ولذلك لا ينبغي الاعتماد عليه للتحديث دون مفتاح توقيع ثابت وآمن.
