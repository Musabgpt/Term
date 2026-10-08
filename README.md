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
