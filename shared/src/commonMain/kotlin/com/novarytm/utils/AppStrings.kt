package com.novarytm.utils

object AppStrings {
    private val en = mapOf(
        "pair_with_partner_title" to "Pair with Partner",
        "link_your_partner" to "Link Your Partner",
        "sign_in_instruction" to "Sign in with Google to upload your encrypted backup, then generate a 5-minute Sync Code to share with your partner securely.",
        "sign_in_button" to "Sign In & Generate Code",
        "sync_code_label" to "5-Minute Sync Code",
        "copy_code" to "Copy Sync Code",
        "copied_toast" to "Copied!",
        "retry_sign_in" to "Retry Sign In & Upload",
        "failed_upload" to "Failed to upload backup to Google Drive. Please try again.",
        "auth_cancelled" to "Google Sign-In was cancelled, or Drive permissions were not granted.",
        "signing_in" to "Signing in and uploading...",
        "connect_title" to "Connect with Partner",
        "enter_details" to "Enter Partner's Details",
        "paste_code_instruction" to "Paste the 5-minute Sync Code provided by your partner.",
        "sync_code_field" to "Sync Code",
        "connect_button" to "Connect and Set PIN",
        "expired_code" to "This Sync Code has expired. Please ask your partner to generate a new one.",
        "invalid_format" to "Invalid code format.",
        "invalid_code" to "Invalid Sync Code."
    )

    private val es = mapOf(
        "pair_with_partner_title" to "Vincular con Pareja",
        "link_your_partner" to "Enlaza a tu Pareja",
        "sign_in_instruction" to "Inicia sesión con Google para subir tu copia de seguridad encriptada y luego genera un Código de Sincronización de 5 minutos para compartirlo de forma segura.",
        "sign_in_button" to "Iniciar Sesión y Generar Código",
        "sync_code_label" to "Código de 5 Minutos",
        "copy_code" to "Copiar Código",
        "copied_toast" to "¡Copiado!",
        "retry_sign_in" to "Reintentar Iniciar Sesión y Subir",
        "failed_upload" to "Error al subir la copia de seguridad a Google Drive. Por favor, inténtalo de nuevo.",
        "auth_cancelled" to "Se canceló el inicio de sesión en Google o no se concedieron los permisos de Drive.",
        "signing_in" to "Iniciando sesión y subiendo...",
        "connect_title" to "Conectar con Pareja",
        "enter_details" to "Ingresa los Detalles de tu Pareja",
        "paste_code_instruction" to "Pega el Código de Sincronización de 5 minutos proporcionado por tu pareja.",
        "sync_code_field" to "Código de Sincronización",
        "connect_button" to "Conectar y Establecer PIN",
        "expired_code" to "Este Código de Sincronización ha expirado. Por favor, pide a tu pareja que genere uno nuevo.",
        "invalid_format" to "Formato de código inválido.",
        "invalid_code" to "Código de Sincronización inválido."
    )

    private val fr = mapOf(
        "pair_with_partner_title" to "Associer avec un partenaire",
        "link_your_partner" to "Liez votre partenaire",
        "sign_in_instruction" to "Connectez-vous avec Google pour télécharger votre sauvegarde chiffrée, puis générez un code de synchronisation de 5 minutes à partager en toute sécurité.",
        "sign_in_button" to "Se connecter et générer le code",
        "sync_code_label" to "Code de synchronisation (5 min)",
        "copy_code" to "Copier le code",
        "copied_toast" to "Copié !",
        "retry_sign_in" to "Réessayer la connexion",
        "failed_upload" to "Échec du téléchargement sur Google Drive. Veuillez réessayer.",
        "auth_cancelled" to "La connexion Google a été annulée ou les autorisations Drive refusées.",
        "signing_in" to "Connexion et téléchargement..."
    )

    private val pt = mapOf(
        "pair_with_partner_title" to "Emparelhar com Parceiro",
        "link_your_partner" to "Vincule seu Parceiro",
        "sign_in_instruction" to "Faça login no Google para fazer o upload do seu backup criptografado e gere um Código de Sincronização de 5 minutos para compartilhar com segurança.",
        "sign_in_button" to "Entrar e Gerar Código",
        "sync_code_label" to "Código de Sincronização (5 min)",
        "copy_code" to "Copiar Código",
        "copied_toast" to "Copiado!",
        "retry_sign_in" to "Tentar novamente o login",
        "failed_upload" to "Falha ao enviar backup para o Google Drive. Tente novamente.",
        "auth_cancelled" to "O Login do Google foi cancelado ou as permissões do Drive não foram concedidas.",
        "signing_in" to "Entrando e enviando..."
    )

    private val de = mapOf(
        "pair_with_partner_title" to "Mit Partner koppeln",
        "link_your_partner" to "Partner verknüpfen",
        "sign_in_instruction" to "Melden Sie sich bei Google an, um Ihr verschlüsseltes Backup hochzuladen, und generieren Sie einen 5-minütigen Sync-Code.",
        "sign_in_button" to "Anmelden & Code generieren",
        "sync_code_label" to "5-Minuten Sync-Code",
        "copy_code" to "Code kopieren",
        "copied_toast" to "Kopiert!",
        "retry_sign_in" to "Anmeldung wiederholen",
        "failed_upload" to "Fehler beim Hochladen auf Google Drive. Bitte versuchen Sie es erneut.",
        "auth_cancelled" to "Die Google-Anmeldung wurde abgebrochen oder Drive-Berechtigungen wurden nicht erteilt.",
        "signing_in" to "Anmelden und hochladen..."
    )

    private val it = mapOf(
        "pair_with_partner_title" to "Associa con Partner",
        "link_your_partner" to "Collega il tuo Partner",
        "sign_in_instruction" to "Accedi a Google per caricare il backup crittografato e genera un codice di sincronizzazione di 5 minuti da condividere in sicurezza.",
        "sign_in_button" to "Accedi e Genera Codice",
        "sync_code_label" to "Codice di Sincronizzazione (5 min)",
        "copy_code" to "Copia Codice",
        "copied_toast" to "Copiato!",
        "retry_sign_in" to "Riprova Accesso",
        "failed_upload" to "Caricamento su Google Drive non riuscito. Riprova.",
        "auth_cancelled" to "Accesso a Google annullato o permessi Drive non concessi.",
        "signing_in" to "Accesso e caricamento..."
    )

    private val ru = mapOf(
        "pair_with_partner_title" to "Связать с партнером",
        "link_your_partner" to "Привяжите партнера",
        "sign_in_instruction" to "Войдите через Google, чтобы загрузить зашифрованную резервную копию, затем создайте 5-минутный код синхронизации.",
        "sign_in_button" to "Войти и создать код",
        "sync_code_label" to "5-минутный код синхронизации",
        "copy_code" to "Копировать код",
        "copied_toast" to "Скопировано!",
        "retry_sign_in" to "Повторить вход",
        "failed_upload" to "Не удалось загрузить резервную копию на Google Drive.",
        "auth_cancelled" to "Вход через Google был отменен, или разрешения Drive не предоставлены.",
        "signing_in" to "Вход и загрузка..."
    )

    private val hi = mapOf(
        "pair_with_partner_title" to "पार्टनर के साथ जोड़ें",
        "link_your_partner" to "अपने पार्टनर को लिंक करें",
        "sign_in_instruction" to "अपने एन्क्रिप्टेड बैकअप को अपलोड करने के लिए Google के साथ साइन इन करें, फिर 5-मिनट का सिंक कोड जेनरेट करें।",
        "sign_in_button" to "साइन इन करें और कोड जेनरेट करें",
        "sync_code_label" to "5-मिनट का सिंक कोड",
        "copy_code" to "कोड कॉपी करें",
        "copied_toast" to "कॉपी किया गया!",
        "retry_sign_in" to "साइन इन फिर से आज़माएं",
        "failed_upload" to "Google ड्राइव पर अपलोड विफल रहा।",
        "auth_cancelled" to "Google साइन-इन रद्द कर दिया गया या ड्राइव अनुमतियां नहीं दी गईं।",
        "signing_in" to "साइन इन और अपलोड हो रहा है..."
    )

    private val ar = mapOf(
        "pair_with_partner_title" to "الاقتران مع الشريك",
        "link_your_partner" to "اربط شريكك",
        "sign_in_instruction" to "قم بتسجيل الدخول باستخدام Google لتحميل نسختك الاحتياطية المشفرة ، ثم قم بإنشاء رمز مزامنة مدته 5 دقيقة.",
        "sign_in_button" to "تسجيل الدخول وإنشاء الرمز",
        "sync_code_label" to "رمز مزامنة (5 دقيقة)",
        "copy_code" to "نسخ الرمز",
        "copied_toast" to "تم النسخ!",
        "retry_sign_in" to "إعادة المحاولة",
        "failed_upload" to "فشل التحميل إلى Google Drive. يرجى المحاولة مرة أخرى.",
        "auth_cancelled" to "تم إلغاء تسجيل الدخول إلى Google ، أو لم يتم منح أذونات Drive.",
        "signing_in" to "جاري تسجيل الدخول والتحميل..."
    )

    private val ja = mapOf(
        "pair_with_partner_title" to "パートナーとペアリング",
        "link_your_partner" to "パートナーをリンク",
        "sign_in_instruction" to "Googleでログインして暗号化されたバックアップをアップロードし、安全に共有するための5分間の同期コードを生成します。",
        "sign_in_button" to "ログインしてコードを生成",
        "sync_code_label" to "5分間の同期コード",
        "copy_code" to "コードをコピー",
        "copied_toast" to "コピーしました！",
        "retry_sign_in" to "ログインを再試行",
        "failed_upload" to "Google Driveへのアップロードに失敗しました。再試行してください。",
        "auth_cancelled" to "Googleログインがキャンセルされたか、Driveの権限が付与されていません。",
        "signing_in" to "ログインしてアップロード中..."
    )

    private val zh = mapOf(
        "pair_with_partner_title" to "与伴侣配对",
        "link_your_partner" to "链接您的伴侣",
        "sign_in_instruction" to "使用Google登录以安全上传您的加密备份，然后生成一个5分钟的同步代码进行安全共享。",
        "sign_in_button" to "登录并生成代码",
        "sync_code_label" to "5分钟同步代码",
        "copy_code" to "复制同步代码",
        "copied_toast" to "已复制！",
        "retry_sign_in" to "重试登录并上传",
        "failed_upload" to "无法上传备份到Google Drive。请重试。",
        "auth_cancelled" to "Google登录被取消，或未授予Drive权限。",
        "signing_in" to "正在登录并上传..."
    )

    private val translations = mapOf(
        "en" to en,
        "es" to es,
        "fr" to fr,
        "pt" to pt,
        "de" to de,
        "it" to it,
        "ru" to ru,
        "hi" to hi,
        "ar" to ar,
        "ja" to ja,
        "zh" to zh
    )

    fun get(key: String): String {
        val lang = getCurrentLanguageCode().take(2).lowercase()
        val stringMap = translations[lang] ?: en
        return stringMap[key] ?: en[key] ?: key
    }
}
