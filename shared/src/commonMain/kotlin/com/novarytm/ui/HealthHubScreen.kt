package com.novarytm.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.material3.HorizontalDivider as HorizontalDivider
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import com.novarytm.db.CycleQueries
import com.novarytm.notifications.NotificationScheduler
import com.novarytm.sync.PartnerPermissions
import kotlin.time.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.todayIn
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HealthHubScreen(
    currentMethod: String?,
    onMethodSelected: (String) -> Unit,
    isPartner: Boolean,
    partnerPerms: PartnerPermissions,
    queries: CycleQueries,
    notificationScheduler: NotificationScheduler
) {
    val tabs = remember(isPartner, partnerPerms) {
        if (isPartner) {
            val list = mutableListOf<String>()
            if (partnerPerms.shareBirthControl) list.add("Birth Control")
            if (partnerPerms.sharePregnancyTests) list.add("Pregnancy Tests")
            list
        } else {
            listOf("Birth Control", "Pregnancy Tests")
        }
    }
    
    var selectedTab by remember { mutableStateOf(0) }
    // Ensure selectedTab stays within bounds if tabs change
    if (tabs.isNotEmpty() && selectedTab >= tabs.size) {
        selectedTab = 0
    }
    
    val primaryColor = LocalAppColors.current.primary

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (tabs.isNotEmpty()) {
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = Color.White,
                contentColor = primaryColor,
                indicator = { tabPositions ->
                    if (selectedTab < tabPositions.size) {
                        TabRowDefaults.SecondaryIndicator(
                            Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                            color = primaryColor
                        )
                    }
                }
            ) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title, fontWeight = FontWeight.Bold) },
                        selectedContentColor = primaryColor,
                        unselectedContentColor = Color.Gray
                    )
                }
            }
        }

        Box(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            if (tabs.isEmpty()) {
                Text(
                    text = "No health data has been shared by the owner.",
                    color = Color.Gray,
                    modifier = Modifier.align(Alignment.Center)
                )
            } else {
                when (tabs[selectedTab]) {
                    "Birth Control" -> {
                        BirthControlTab(
                            currentMethod = currentMethod,
                            onMethodSelected = onMethodSelected,
                            isPartner = isPartner,
                            queries = queries,
                            notificationScheduler = notificationScheduler,
                            primaryColor = primaryColor
                        )
                    }
                    "Pregnancy Tests" -> {
                        PregnancyTestTab(
                            isPartner = isPartner,
                            queries = queries,
                            notificationScheduler = notificationScheduler,
                            primaryColor = primaryColor
                        )
                    }
                }
            }
        }
    }
}
// Appended Birth Control Quiz components

data class BCOption(
    val id: String, 
    val name: String, 
    val type: String, 
    val mechanism: String, 
    val effectiveness: String, 
    val sideEffects: List<String>,
    val frequency: String, // Daily, Weekly, Monthly, Quarterly, Yearly, On-demand
    val hormones: String, // Estrogen + Progestin, Progestin-only, Non-Hormonal
    val hasEstrogen: Boolean
)

val bcOptions = listOf(
    BCOption("pill-combined", "Combined Oral Pill", "Hormonal", "Prevents ovulation, thickens cervical mucus.", "91%", listOf("Nausea", "Headaches", "Mood changes", "Breast tenderness"), "Daily", "Estrogen + Progestin", true),
    BCOption("pill-mini", "Mini-Pill", "Hormonal", "Thickens cervical mucus to block sperm from reaching the egg.", "91%", listOf("Irregular bleeding", "Headaches", "Nausea", "Breast tenderness"), "Daily", "Progestin-only", false),
    BCOption("hormonal-iud-8", "Hormonal IUD (Mirena/Liletta)", "Hormonal", "Releases progestin locally, thickening cervical mucus.", "99%", listOf("Irregular bleeding", "Cramping", "Headaches", "Ovarian cysts"), "Years (8)", "Progestin-only", false),
    BCOption("hormonal-iud-5", "Hormonal IUD (Kyleena)", "Hormonal", "Releases progestin locally, thickening cervical mucus.", "99%", listOf("Irregular bleeding", "Cramping", "Headaches", "Ovarian cysts"), "Years (5)", "Progestin-only", false),
    BCOption("hormonal-iud-3", "Hormonal IUD (Skyla)", "Hormonal", "Releases progestin locally, thickening cervical mucus.", "99%", listOf("Irregular bleeding", "Cramping", "Headaches", "Ovarian cysts"), "Years (3)", "Progestin-only", false),
    BCOption("copper-iud", "Copper IUD", "Non-Hormonal", "Creates an inflammatory response toxic to sperm.", "99%", listOf("Heavier periods", "More cramping", "Spotting"), "Years (10)", "Non-Hormonal", false),
    BCOption("implant", "Arm Implant", "Hormonal", "Releases progestin to prevent ovulation.", "99%", listOf("Irregular bleeding", "Headaches", "Breast pain", "Nausea"), "Years (3)", "Progestin-only", false),
    BCOption("patch", "Contraceptive Patch", "Hormonal", "Releases hormones through the skin to prevent ovulation.", "91%", listOf("Skin irritation", "Breast tenderness", "Headaches", "Nausea"), "Weekly", "Estrogen + Progestin", true),
    BCOption("ring", "Vaginal Ring", "Hormonal", "Flexible ring that releases estrogen and progestin.", "91%", listOf("Vaginal irritation", "Headaches", "Nausea", "Breast tenderness"), "Monthly", "Estrogen + Progestin", true),
    BCOption("shot", "Contraceptive Injection", "Hormonal", "Prevents ovulation via progestin injection.", "94%", listOf("Weight gain", "Irregular bleeding", "Headaches", "Nervousness"), "Every 3 Months", "Progestin-only", false),
    BCOption("barriers", "Barrier Methods", "Non-Hormonal", "Physically blocks sperm (Condoms, Diaphragms).", "82%", listOf("Latex allergy", "Reduced spontaneity"), "On-demand", "Non-Hormonal", false)
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BirthControlTab(
    currentMethod: String?,
    onMethodSelected: (String) -> Unit,
    isPartner: Boolean,
    queries: com.novarytm.db.CycleQueries,
    notificationScheduler: com.novarytm.notifications.NotificationScheduler,
    primaryColor: Color
) {
    var expandedCard by remember { mutableStateOf<String?>(null) }
    var showQuiz by remember { mutableStateOf(false) }
    var showMethodSelector by remember { mutableStateOf(false) }
    var quizResult by remember { mutableStateOf<String?>(null) }
    
    val backgroundColor = MaterialTheme.colorScheme.background

    Box(modifier = Modifier.fillMaxSize().background(backgroundColor)) {
        Column(modifier = Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState())) {
            
            // Current Method Section
            if (currentMethod != null) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = primaryColor.copy(alpha = 0.05f)),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp).border(1.dp, primaryColor.copy(alpha = 0.2f), RoundedCornerShape(20.dp))
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Verified, contentDescription = null, tint = primaryColor)
                            Spacer(Modifier.width(8.dp))
                            Text("Your Active Method", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = primaryColor)
                        }
                        Text(currentMethod, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold))
                        
                        Spacer(Modifier.height(16.dp))
                        
                        // Recommendations based on method
                        val recommendation = when {
                            currentMethod.contains("Pill", ignoreCase = true) -> "Take your pill at the same time every day for maximum effectiveness."
                            currentMethod.contains("IUD", ignoreCase = true) -> "Check your IUD strings monthly and note the expiration date."
                            currentMethod.contains("Injection", ignoreCase = true) -> "Schedule your next appointment for 12 weeks from your last shot."
                            currentMethod.contains("Patch", ignoreCase = true) -> "Apply a new patch on the same day every week for 3 weeks."
                            currentMethod.contains("Ring", ignoreCase = true) -> "Keep the ring in place for 3 weeks, then remove for 1 week."
                            else -> "Consult with your doctor if you experience unusual side effects."
                        }
                        
                        Box(modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(Color.White).padding(16.dp)) {
                            Row {
                                Icon(Icons.Default.TipsAndUpdates, contentDescription = null, tint = Color(0xFFB07D3E), modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(12.dp))
                                Text(recommendation, style = MaterialTheme.typography.bodyMedium, color = Color.DarkGray)
                            }
                        }
                    }
                }
            }

            // Disclaimer
            Card(
                colors = CardDefaults.cardColors(containerColor = Color.White),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp).border(1.dp, Color.LightGray.copy(alpha = 0.3f), RoundedCornerShape(20.dp))
            ) {
                Row(modifier = Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(56.dp).clip(CircleShape).background(primaryColor.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.Info, contentDescription = null, tint = primaryColor)
                    }
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text("Medical Disclaimer", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold), color = LocalAppColors.current.textPrimary)
                        Text("Educational Reference Only: Bloom provides compatibility insights based on algorithmic inputs. Bloom is a cycle tracking and educational tool, NOT a contraceptive device. We do not provide medical advice. Always consult a qualified healthcare professional.", style = MaterialTheme.typography.bodyMedium, color = LocalAppColors.current.textPrimary.copy(alpha=0.8f), lineHeight = 20.sp)
                    }
                }
            }

            if (currentMethod != null && !isPartner) {
                ReminderSection(
                    currentMethod = currentMethod,
                    queries = queries,
                    notificationScheduler = notificationScheduler,
                    primaryColor = primaryColor
                )
            }

            // CTA
            if (!isPartner) {
                Row(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = { showQuiz = true },
                        modifier = Modifier.weight(1f).height(64.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = if (currentMethod != null) Color.White else primaryColor, contentColor = if (currentMethod != null) primaryColor else Color.White),
                        border = if (currentMethod != null) BorderStroke(1.dp, primaryColor) else null
                    ) {
                        Text("Take Quiz", style = MaterialTheme.typography.titleMedium)
                    }

                    Button(
                        onClick = { showMethodSelector = true },
                        modifier = Modifier.weight(1f).height(64.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = primaryColor, contentColor = Color.White)
                    ) {
                        Text(if (currentMethod != null) "Update Method" else "Set Method", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }

            if (quizResult != null) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = LocalAppColors.current.secondary.copy(alpha = 0.2f)),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text("Quiz Recommendation", style = MaterialTheme.typography.titleMedium, color = primaryColor)
                        Spacer(Modifier.height(8.dp))
                        Text("Based on your answers, we recommend exploring: $quizResult", style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }

            Text("Explore Options", style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold), modifier = Modifier.padding(bottom = 16.dp))

            // Options List
            bcOptions.forEach { option ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp).border(1.dp, Color.LightGray.copy(alpha = 0.3f), RoundedCornerShape(20.dp))
                ) {
                    Column(modifier = Modifier.fillMaxWidth().clickable { expandedCard = if (expandedCard == option.id) null else option.id }) {
                        Row(modifier = Modifier.padding(20.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val isHormonal = option.type == "Hormonal"
                                ContraceptiveMethodRing(
                                    effectivenessText = option.effectiveness,
                                    isHormonal = isHormonal
                                )
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text(option.name, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
                                    Text(option.type, style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
                                }
                            }
                            Icon(if (expandedCard == option.id) Icons.Default.KeyboardArrowDown else Icons.Default.ChevronRight, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(28.dp))
                        }
                        
                        AnimatedVisibility(visible = expandedCard == option.id) {
                            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp).border(0.dp, Color.Transparent)) {
                                HorizontalDivider(color = Color.LightGray.copy(alpha = 0.3f))
                                Spacer(Modifier.height(16.dp))
                                
                                Row(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                                    InfoChip(Icons.Default.Schedule, option.frequency)
                                    Spacer(Modifier.width(8.dp))
                                    InfoChip(Icons.Default.Opacity, option.hormones)
                                }

                                Text("How it works", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold), color = LocalAppColors.current.textPrimary)
                                Text(option.mechanism, style = MaterialTheme.typography.bodyLarge, color = LocalAppColors.current.textPrimary.copy(alpha = 0.9f), modifier = Modifier.padding(bottom = 16.dp))
                                
                                Text("Possible side effects", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold), color = LocalAppColors.current.textPrimary, modifier = Modifier.padding(bottom = 12.dp))
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    option.sideEffects.forEach { effect ->
                                        Box(
                                            modifier = Modifier
                                                .clip(CircleShape)
                                                .background(LocalAppColors.current.secondary.copy(alpha = 0.2f))
                                                .padding(horizontal = 16.dp, vertical = 8.dp)
                                        ) {
                                            Text(effect, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium), color = LocalAppColors.current.textPrimary)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (showQuiz) {
            QuizOverlay(
                onClose = { showQuiz = false }, 
                onComplete = { 
                    quizResult = it.name
                    showQuiz = false 
                }, 
                primaryColor = primaryColor
            )
        }

        if (showMethodSelector) {
            Dialog(onDismissRequest = { showMethodSelector = false }) {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White)
                ) {
                    Column(modifier = Modifier.padding(20.dp).fillMaxWidth()) {
                        Text("Select Active Method", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
                        Spacer(Modifier.height(16.dp))
                        Column(modifier = Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                            bcOptions.forEach { option ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().clickable { 
                                        onMethodSelected(option.name)
                                        showMethodSelector = false
                                    }.padding(vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(option.name, style = MaterialTheme.typography.bodyLarge)
                                }
                                HorizontalDivider(color = Color.LightGray.copy(alpha = 0.3f))
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable { 
                                    onMethodSelected("None")
                                    showMethodSelector = false
                                }.padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("None", style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        TextButton(onClick = { showMethodSelector = false }, modifier = Modifier.align(Alignment.End)) {
                            Text("Cancel", color = primaryColor)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun InfoChip(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(LocalAppColors.current.secondary.copy(alpha = 0.15f)).padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color.Gray)
        Spacer(Modifier.width(4.dp))
        Text(text, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
    }
}

@Composable
fun QuizOverlay(onClose: () -> Unit, onComplete: (BCOption) -> Unit, primaryColor: Color) {
    var step by remember { mutableStateOf(0) }
    val totalSteps = 10
    
    // Inputs
    var maintenance by remember { mutableStateOf<String?>(null) }
    var hormones by remember { mutableStateOf<String?>(null) }
    var periodGoal by remember { mutableStateOf<String?>(null) }
    var estrogenContra by remember { mutableStateOf<Boolean?>(null) }
    var stiPriority by remember { mutableStateOf<Boolean?>(null) }
    var overWeightThreshold by remember { mutableStateOf<Boolean?>(null) }
    var postpartumStatus by remember { mutableStateOf<Boolean?>(null) }
    var managingConditions by remember { mutableStateOf<Boolean?>(null) }
    var acneGoal by remember { mutableStateOf<Boolean?>(null) }
    var highBP by remember { mutableStateOf<Boolean?>(null) }
    
    val recommendations = remember(step) {
        if (step == totalSteps) {
            var filtered = bcOptions.toList()
            
            // HARD FILTERS (Dealbreakers for Estrogen)
            val hasEstrogenVeto = estrogenContra == true || postpartumStatus == true || highBP == true
            if (hasEstrogenVeto) {
                filtered = filtered.filter { !it.hasEstrogen }
            }
            
            // HORMONE PREFERENCE FILTER
            if (hormones == "Zero") filtered = filtered.filter { it.type == "Non-Hormonal" }
            if (hormones == "Low") filtered = filtered.filter { it.type == "Non-Hormonal" || it.id == "hormonal-iud" || it.id == "ring" || it.id == "pill-mini" }
            
            filtered.sortedByDescending { option ->
                var score = 0
                
                // Maintenance Matching
                val m = maintenance
                if (m != null && option.frequency.contains(m, ignoreCase = true)) score += 15
                
                // Condition Management (PCOS, Endo, Cramps)
                if (managingConditions == true) {
                    if (option.id == "hormonal-iud" || option.id == "pill-combined") score += 20
                    if (option.id == "copper-iud") score -= 15 // Exacerbates cramps
                }
                
                // Period Goals
                if (periodGoal == "Lighter") {
                    if (option.id == "hormonal-iud" || option.id == "pill-combined" || option.id == "shot" || option.id == "implant") score += 10
                }
                if (periodGoal == "Regular" && (option.id == "pill-combined" || option.id == "ring")) score += 10
                
                // Acne Goal
                if (acneGoal == true && option.id == "pill-combined") score += 15
                
                // Weight Consideration (Patch)
                if (overWeightThreshold == true && option.id == "patch") score -= 20
                
                score
            }.let { ranked ->
                val list = ranked.toMutableList()
                if (stiPriority == true && !list.any { it.id == "barriers" }) {
                    bcOptions.find { it.id == "barriers" }?.let { list.add(0, it) }
                }
                list.distinctBy { it.id }.take(3)
            }
        } else emptyList()
    }
    
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(modifier = Modifier.fillMaxSize().background(Color.White), contentAlignment = Alignment.Center) {
            IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopEnd).padding(16.dp).background(Color.LightGray.copy(alpha=0.2f), CircleShape)) {
                Icon(Icons.Default.Close, contentDescription = null)
            }
            
            Column(
                modifier = Modifier.fillMaxWidth().padding(32.dp).verticalScroll(rememberScrollState()), 
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (step < totalSteps) {
                    LinearProgressIndicator(
                        progress = { (step + 1) / totalSteps.toFloat() },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp).clip(CircleShape).height(8.dp),
                        color = primaryColor,
                        trackColor = primaryColor.copy(alpha = 0.1f)
                    )
                    
                    val isClinicalStep = step >= 3 && step != 4
                    if (isClinicalStep) {
                        PrivacyNote()
                        Spacer(Modifier.height(16.dp))
                    }
                    when (step) {
                        0 -> QuizStep("1. Maintenance", "How often do you want to think about your birth control?", listOf("Every day (Pills)", "Every week/month", "Every few months", "Set it and forget it (Years)", "Only when I have sex"), { maintenance = it; step++ })
                        1 -> QuizStep("2. Hormones", "How do you feel about using hormones?", listOf("I'm fine with them", "I prefer low-dose/localized", "I want zero hormones (Copper IUD, Barriers)"), { hormones = when(it) { "I prefer low-dose/localized" -> "Low"; "I want zero hormones (Copper IUD, Barriers)" -> "Zero"; else -> "Fine" }; step++ })
                        2 -> QuizStep("3. Period Impact", "What is your ideal scenario for your period?", listOf("Lighter or stop entirely", "Regular and predictable", "Natural, unaltered cycle", "I don't mind cramping/heaviness"), { periodGoal = when(it) { "Lighter or stop entirely" -> "Lighter"; "Regular and predictable" -> "Re dgular"; "Natural, unaltered cycle" -> "Natural"; else -> "Neutral" }; step++ })
                        3 -> QuizStep("4. Safety Check", "Do any of these apply: Migraines with aura, history of blood clots, or smoker over 35?", listOf("Yes", "No"), { estrogenContra = (it == "Yes"); step++ })
                        4 -> QuizStep("5. Protection", "Is protecting against STIs a priority right now?", listOf("Yes", "No"), { stiPriority = (it == "Yes"); step++ })
                        5 -> QuizStep("6. Weight", "Do you weigh over 198 lbs (90 kg)?", listOf("Yes", "No", "Prefer not to say"), { overWeightThreshold = (it == "Yes"); step++ })
                        6 -> QuizStep("7. Postpartum", "Have you given birth in the last 6 weeks, or are you exclusively breastfeeding?", listOf("Yes", "No"), { postpartumStatus = (it == "Yes"); step++ })
                        7 -> QuizStep("8. Symptoms", "Are you looking to manage symptoms of PCOS, Endometriosis, or severe cramps?", listOf("Yes", "No"), { managingConditions = (it == "Yes"); step++ })
                        8 -> QuizStep("9. Skin & Acne", "Is clearing up acne a primary goal for your birth control?", listOf("Yes", "No"), { acneGoal = (it == "Yes"); step++ })
                        9 -> QuizStep("10. Blood Pressure", "Do you have a history of high blood pressure (hypertension)?", listOf("Yes", "No"), { highBP = (it == "Yes"); step++ })
                    }
                } else {
                    // Results Screen
                    Box(modifier = Modifier.size(64.dp).clip(CircleShape).background(primaryColor.copy(alpha = 0.1f)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.MedicalServices, contentDescription = null, tint = primaryColor, modifier = Modifier.size(32.dp))
                    }
                    Spacer(Modifier.height(16.dp))
                    Text("Your Clinical Triage Results", style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold))
                    Spacer(Modifier.height(8.dp))
                    Text("Based on your physiological profile and preferences.", style = MaterialTheme.typography.bodyMedium, color = Color.Gray, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(24.dp))
                    
                    // Medical Disclaimer
                    Card(
                        colors = CardDefaults.cardColors(containerColor = primaryColor.copy(alpha = 0.15f)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)
                    ) {
                        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Info, contentDescription = null, tint = primaryColor, modifier = Modifier.size(24.dp))
                            Spacer(Modifier.width(12.dp))
                            Text("Educational Reference Only: Bloom provides compatibility insights based on algorithmic inputs. Bloom is a cycle tracking and educational tool, NOT a contraceptive device. We do not provide medical advice. Always consult a qualified healthcare professional.", style = MaterialTheme.typography.labelSmall, color = primaryColor, lineHeight = 14.sp)
                        }
                    }

                    if (estrogenContra == true || postpartumStatus == true || highBP == true) {
                        Box(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp).clip(RoundedCornerShape(8.dp)).background(LocalAppColors.current.secondary.copy(alpha = 0.15f)).padding(12.dp)) {
                            Text("⚠️ Some methods were filtered out due to safety contraindications (Estrogen risk).", style = MaterialTheme.typography.labelSmall, color = LocalAppColors.current.textPrimary)
                        }
                    }

                    recommendations.forEach { result ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color.White), 
                            border = BorderStroke(1.dp, primaryColor.copy(alpha = 0.2f)), 
                            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                    Text(result.name, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                                    if (overWeightThreshold == true && result.id == "patch") {
                                        Icon(Icons.Default.Warning, contentDescription = "Efficacy Warning", tint = Color(0xFFB07D3E), modifier = Modifier.size(18.dp))
                                    }
                                }
                                Text("${result.effectiveness} effective • ${result.frequency}", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                                if (overWeightThreshold == true && result.id == "patch") {
                                    Text("May have reduced efficacy at your current weight.", style = MaterialTheme.typography.labelSmall, color = Color(0xFFB07D3E), modifier = Modifier.padding(top = 4.dp))
                                }
                                Spacer(Modifier.height(8.dp))
                                Text(result.mechanism, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    
                    Spacer(Modifier.height(32.dp))
                    Button(
                        onClick = { onComplete(recommendations.first()) }, 
                        modifier = Modifier.fillMaxWidth().height(56.dp), 
                        colors = ButtonDefaults.buttonColors(containerColor = primaryColor),
                        shape = CircleShape
                    ) {
                        Text("Done", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }
}

@Composable
fun PrivacyNote() {
    val fertileColor = LocalAppColors.current.secondary
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(fertileColor.copy(alpha = 0.1f)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Lock, contentDescription = null, tint = fertileColor, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(12.dp))
        Text("Your health data is processed offline and never leaves this device.", style = MaterialTheme.typography.labelSmall, color = fertileColor)
    }
}

@Composable
fun QuizStep(title: String, prompt: String, options: List<String>, onSelect: (String) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title.uppercase(), style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.sp), color = Color.Gray, modifier = Modifier.padding(bottom = 8.dp))
        Text(prompt, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold), textAlign = TextAlign.Center, modifier = Modifier.padding(bottom = 32.dp))
        options.forEach { option ->
            Button(
                onClick = { onSelect(option) }, 
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp).height(56.dp), 
                colors = ButtonDefaults.buttonColors(containerColor = LocalAppColors.current.secondary.copy(alpha = 0.2f), contentColor = LocalAppColors.current.textPrimary),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(option, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
fun ContraceptiveMethodRing(
    effectivenessText: String,
    isHormonal: Boolean
) {
    val ringColor = if (isHormonal) LocalAppColors.current.primary else LocalAppColors.current.secondary
    val percentage = effectivenessText.replace("%", "").toFloatOrNull()?.div(100f) ?: 0.9f

    Box(modifier = Modifier.size(56.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            progress = { percentage },
            color = ringColor,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxSize(),
            strokeWidth = 4.dp
        )
        Text(
            effectivenessText, 
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), 
            color = ringColor,
            maxLines = 1,
            textAlign = TextAlign.Center
        )
    }
}
@OptIn(ExperimentalMaterial3Api::class, kotlin.time.ExperimentalTime::class)
@Composable
fun ReminderSection(
    currentMethod: String,
    queries: com.novarytm.db.CycleQueries,
    notificationScheduler: com.novarytm.notifications.NotificationScheduler,
    primaryColor: Color
) {
    // Basic reminder setup.
    var showSetup by remember { mutableStateOf(false) }
    
    val isPill = currentMethod.contains("Pill", ignoreCase = true)
    val isHormonalIUD = currentMethod.contains("Hormonal IUD", ignoreCase = true)
    val isCopperIUD = currentMethod.contains("Copper IUD", ignoreCase = true)
    val isImplant = currentMethod.contains("Implant", ignoreCase = true)
    val isPatch = currentMethod.contains("Patch", ignoreCase = true)
    val isRing = currentMethod.contains("Ring", ignoreCase = true)
    val isInjection = currentMethod.contains("Injection", ignoreCase = true)

    val isValidMethod = isPill || isHormonalIUD || isCopperIUD || isImplant || isPatch || isRing || isInjection
    
    if (!isValidMethod) return

    val reminderTitle = "Reminders"
    var reminderDesc = ""
    var dateLabel = "Start Date"
    val showTimeRow = true
    
    when {
        isPill -> {
            reminderDesc = "Set a daily reminder to take your pill."
        }
        isHormonalIUD || isCopperIUD -> {
            val iudYears = when {
                currentMethod.contains("8") || currentMethod.contains("Mirena", ignoreCase = true) || currentMethod.contains("Liletta", ignoreCase = true) -> 8
                currentMethod.contains("3") || currentMethod.contains("Skyla", ignoreCase = true) -> 3
                currentMethod.contains("Copper", ignoreCase = true) || currentMethod.contains("10") -> 10
                else -> 5
            }
            reminderDesc = "Set a reminder for when to replace your IUD ($iudYears years), and a monthly string check."
            dateLabel = "Insertion Date"
        }
        isImplant -> {
            reminderDesc = "Set a reminder for when to replace your Arm Implant (3 years)."
            dateLabel = "Insertion Date"
        }
        isPatch -> {
            reminderDesc = "Set a weekly reminder to change your patch (3 weeks on, 1 week off)."
        }
        isRing -> {
            reminderDesc = "Set reminders to remove your ring (after 3 weeks) and insert a new one (after 1 week)."
        }
        isInjection -> {
            reminderDesc = "Set a reminder for your next shot (every 12 weeks)."
            dateLabel = "Date of Last Shot"
        }
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp).border(1.dp, primaryColor.copy(alpha = 0.3f), RoundedCornerShape(20.dp))
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(reminderTitle, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = primaryColor)
            Spacer(Modifier.height(8.dp))
            Text(reminderDesc, style = MaterialTheme.typography.bodyMedium)
            
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { showSetup = true },
                colors = ButtonDefaults.buttonColors(containerColor = primaryColor),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Setup Reminder")
            }
        }
    }
    
    if (showSetup) {
        var showTimePicker by remember { mutableStateOf(false) }
        var selectedTimeStr by remember { mutableStateOf("09:00") }
        var showDatePicker by remember { mutableStateOf(false) }
        var selectedDate by remember { mutableStateOf(Clock.System.todayIn(TimeZone.currentSystemDefault())) }

        if (showDatePicker) {
            val datePickerState = rememberDatePickerState(
                initialSelectedDateMillis = Clock.System.now().toEpochMilliseconds()
            )
            DatePickerDialog(
                onDismissRequest = { showDatePicker = false },
                colors = DatePickerDefaults.colors(containerColor = LocalAppColors.current.surface),
                confirmButton = {
                    TextButton(onClick = {
                        datePickerState.selectedDateMillis?.let { millis ->
                            val inst = kotlin.time.Instant.fromEpochMilliseconds(millis)
                            selectedDate = inst.toLocalDateTime(TimeZone.UTC).date
                        }
                        showDatePicker = false
                    }) { Text("OK", color = primaryColor) }
                },
                dismissButton = {
                    TextButton(onClick = { showDatePicker = false }) { Text("Cancel") }
                }
            ) {
                DatePicker(
                    state = datePickerState,
                    colors = DatePickerDefaults.colors(
                        selectedDayContainerColor = primaryColor,
                        todayDateBorderColor = primaryColor,
                        todayContentColor = primaryColor
                    )
                )
            }
        }

        if (showTimePicker) {
            val timePickerState = rememberTimePickerState(initialHour = 9, initialMinute = 0)
            AlertDialog(
                onDismissRequest = { showTimePicker = false },
                containerColor = LocalAppColors.current.surface,
                title = { Text("Select Time") },
                text = {
                    TimePicker(
                        state = timePickerState,
                        colors = TimePickerDefaults.colors(
                            selectorColor = primaryColor,
                            timeSelectorSelectedContainerColor = primaryColor.copy(alpha = 0.2f),
                            timeSelectorSelectedContentColor = primaryColor
                        )
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        selectedTimeStr = "${timePickerState.hour.toString().padStart(2, '0')}:${timePickerState.minute.toString().padStart(2, '0')}"
                        showTimePicker = false
                    }) { Text("OK", color = primaryColor) }
                },
                dismissButton = {
                    TextButton(onClick = { showTimePicker = false }) { Text("Cancel") }
                }
            )
        }

        AlertDialog(
            onDismissRequest = { showSetup = false },
            containerColor = LocalAppColors.current.surface,
            title = { Text("Setup Reminder") },
            text = {
                Column {
                    Text("Reminder scheduled! (Local Push Notification will be sent.)")
                    Spacer(Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { showDatePicker = true }.padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(dateLabel, style = MaterialTheme.typography.bodyLarge)
                        Text(selectedDate.toString(), color = primaryColor, fontWeight = FontWeight.Bold)
                    }
                    HorizontalDivider(color = Color.LightGray.copy(alpha = 0.3f))
                    if (showTimeRow) {
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { showTimePicker = true }.padding(vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Time", style = MaterialTheme.typography.bodyLarge)
                            Text(selectedTimeStr, color = primaryColor, fontWeight = FontWeight.Bold)
                        }
                        HorizontalDivider(color = Color.LightGray.copy(alpha = 0.3f))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val dateStr = selectedDate.toString()
                    val tz = TimeZone.currentSystemDefault()
                    val now = Clock.System.now()
                    val today = Clock.System.todayIn(tz)
                    
                    val timeParts = selectedTimeStr.split(":")
                    val targetTime = LocalTime(timeParts[0].toInt(), timeParts[1].toInt())
                    
                    var firstOccurrence = selectedDate
                    if (firstOccurrence < today) firstOccurrence = today
                    if (LocalDateTime(firstOccurrence, targetTime).toInstant(tz) < now) {
                        firstOccurrence = firstOccurrence.plus(1, DateTimeUnit.DAY)
                    }
                    val recurringDelay = (LocalDateTime(firstOccurrence, targetTime).toInstant(tz).toEpochMilliseconds() - now.toEpochMilliseconds()).coerceAtLeast(1000L)
                    
                    fun delayFor(date: kotlinx.datetime.LocalDate): Long {
                        return (LocalDateTime(date, targetTime).toInstant(tz).toEpochMilliseconds() - now.toEpochMilliseconds()).coerceAtLeast(1000L)
                    }

                    when {
                        isPill -> {
                            queries.insertReminder("Pill", "Daily", selectedTimeStr, dateStr, true)
                            notificationScheduler.scheduleNotification(101, "Bloom", "💊 Time for your pill", recurringDelay)
                        }
                        isHormonalIUD -> {
                            val iudYears = when {
                                currentMethod.contains("8") || currentMethod.contains("Mirena", ignoreCase = true) || currentMethod.contains("Liletta", ignoreCase = true) -> 8
                                currentMethod.contains("3") || currentMethod.contains("Skyla", ignoreCase = true) -> 3
                                else -> 5
                            }
                            val nextDate = selectedDate.plus(iudYears, DateTimeUnit.YEAR)
                            queries.insertReminder("Hormonal IUD Replacement ($iudYears Years)", "Once", selectedTimeStr, nextDate.toString(), true)
                            queries.insertReminder("IUD String Check", "Monthly", selectedTimeStr, dateStr, true)
                            notificationScheduler.scheduleNotification(102, "Bloom", "🌸 Check your strings", recurringDelay)
                            notificationScheduler.scheduleNotification(106, "Bloom", "🌸 Time for Hormonal IUD replacement ($iudYears Years)", delayFor(nextDate))
                        }
                        isCopperIUD -> {
                            val iudYears = when {
                                currentMethod.contains("8") || currentMethod.contains("Mirena", ignoreCase = true) || currentMethod.contains("Liletta", ignoreCase = true) -> 8
                                currentMethod.contains("3") || currentMethod.contains("Skyla", ignoreCase = true) -> 3
                                currentMethod.contains("Copper", ignoreCase = true) || currentMethod.contains("10") -> 10
                                else -> 10
                            }
                            val nextDate = selectedDate.plus(iudYears, DateTimeUnit.YEAR)
                            queries.insertReminder("Copper IUD Replacement ($iudYears Years)", "Once", selectedTimeStr, nextDate.toString(), true)
                            queries.insertReminder("IUD String Check", "Monthly", selectedTimeStr, dateStr, true)
                            notificationScheduler.scheduleNotification(102, "Bloom", "🌸 Check your strings", recurringDelay)
                            notificationScheduler.scheduleNotification(107, "Bloom", "🌸 Time for Copper IUD replacement ($iudYears Years)", delayFor(nextDate))
                        }
                        isImplant -> {
                            val nextDate = selectedDate.plus(3, DateTimeUnit.YEAR)
                            queries.insertReminder("Arm Implant Replacement", "Once", selectedTimeStr, nextDate.toString(), true)
                            notificationScheduler.scheduleNotification(103, "Bloom", "🌸 Time for implant replacement", delayFor(nextDate))
                        }
                        isPatch -> {
                            queries.insertReminder("Contraceptive Patch", "Weekly (3 on, 1 off)", selectedTimeStr, dateStr, true)
                            notificationScheduler.scheduleNotification(104, "Bloom", "🌸 Time to change your patch", recurringDelay)
                        }
                        isRing -> {
                            queries.insertReminder("Vaginal Ring (Remove)", "Every 3 Weeks", selectedTimeStr, selectedDate.plus(21, DateTimeUnit.DAY).toString(), true)
                            queries.insertReminder("Vaginal Ring (Insert)", "Every 4 Weeks", selectedTimeStr, selectedDate.plus(28, DateTimeUnit.DAY).toString(), true)
                            notificationScheduler.scheduleNotification(105, "Bloom", "🌸 Time to change your ring", delayFor(selectedDate.plus(21, DateTimeUnit.DAY)))
                        }
                        isInjection -> {
                            val nextDate = selectedDate.plus(84, DateTimeUnit.DAY)
                            queries.insertReminder("Contraceptive Injection", "Every 12 Weeks", selectedTimeStr, nextDate.toString(), true)
                            notificationScheduler.scheduleNotification(106, "Bloom", "🌸 Time for your next shot", delayFor(nextDate))
                        }
                    }
                    showSetup = false
                }) {
                    Text("Save", color = primaryColor)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSetup = false }) { Text("Cancel") }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, kotlin.time.ExperimentalTime::class)
@Composable
fun PregnancyTestTab(
    isPartner: Boolean,
    queries: com.novarytm.db.CycleQueries,
    notificationScheduler: com.novarytm.notifications.NotificationScheduler,
    primaryColor: Color
) {
    var showLogDialog by remember { mutableStateOf(false) }
    var showReminderDialog by remember { mutableStateOf(false) }
    
    // In a real app we'd observe the DB, for this demo we query on load/update.
    var tests by remember { mutableStateOf(queries.selectAllPregnancyTests().executeAsList()) }
    
    val refreshTests = { tests = queries.selectAllPregnancyTests().executeAsList() }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        if (!isPartner) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Button(onClick = { showLogDialog = true }, colors = ButtonDefaults.buttonColors(containerColor = primaryColor)) {
                    Text("Log Test")
                }
                OutlinedButton(onClick = { showReminderDialog = true }) {
                    Text("Monthly Reminder", color = primaryColor)
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        Text("Test History", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
        Spacer(Modifier.height(8.dp))
        
        if (tests.isEmpty()) {
            Text("No tests logged yet.", color = Color.Gray, style = MaterialTheme.typography.bodyMedium)
        } else {
            tests.forEach { pt ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    border = BorderStroke(1.dp, Color.LightGray.copy(alpha=0.5f))
                ) {
                    Row(modifier = Modifier.padding(16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(com.novarytm.utils.formatDateByRegion(pt.date), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = pt.result,
                            fontWeight = FontWeight.Bold,
                            color = when (pt.result) {
                                "Positive" -> Color(0xFFD32F2F)
                                "Negative" -> Color(0xFF388E3C)
                                else -> Color.Gray
                            }
                        )
                    }
                }
            }
        }
    }
    
    if (showLogDialog) {
        var selectedResult by remember { mutableStateOf("Negative") }
        var showDatePicker by remember { mutableStateOf(false) }
        var selectedDate by remember { mutableStateOf(Clock.System.todayIn(TimeZone.currentSystemDefault())) }

        if (showDatePicker) {
            val datePickerState = rememberDatePickerState(
                initialSelectedDateMillis = Clock.System.now().toEpochMilliseconds()
            )
            DatePickerDialog(
                onDismissRequest = { showDatePicker = false },
                colors = DatePickerDefaults.colors(containerColor = LocalAppColors.current.surface),
                confirmButton = {
                    TextButton(onClick = {
                        datePickerState.selectedDateMillis?.let { millis ->
                            val inst = kotlin.time.Instant.fromEpochMilliseconds(millis)
                            selectedDate = inst.toLocalDateTime(TimeZone.UTC).date
                        }
                        showDatePicker = false
                    }) { Text("OK", color = primaryColor) }
                },
                dismissButton = {
                    TextButton(onClick = { showDatePicker = false }) { Text("Cancel") }
                }
            ) {
                DatePicker(
                    state = datePickerState,
                    colors = DatePickerDefaults.colors(
                        selectedDayContainerColor = primaryColor,
                        todayDateBorderColor = primaryColor,
                        todayContentColor = primaryColor
                    )
                )
            }
        }

        AlertDialog(
            onDismissRequest = { showLogDialog = false },
            containerColor = LocalAppColors.current.surface,
            title = { Text("Log Pregnancy Test") },
            text = {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { showDatePicker = true }.padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Date", style = MaterialTheme.typography.bodyLarge)
                        Text(com.novarytm.utils.formatDateByRegion(selectedDate.toString()), color = primaryColor, fontWeight = FontWeight.Bold)
                    }
                    HorizontalDivider(color = Color.LightGray.copy(alpha = 0.3f))
                    Spacer(Modifier.height(16.dp))
                    Text("What was the result?")
                    Spacer(Modifier.height(8.dp))
                    listOf("Negative", "Positive", "Invalid/Wrong").forEach { res ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { selectedResult = res }.padding(4.dp)) {
                            RadioButton(
                                selected = selectedResult == res, 
                                onClick = { selectedResult = res },
                                colors = RadioButtonDefaults.colors(selectedColor = primaryColor)
                            )
                            Text(res, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    queries.insertPregnancyTest(selectedDate.toString(), selectedResult)
                    refreshTests()
                    showLogDialog = false
                }) {
                    Text("Save", color = primaryColor)
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogDialog = false }) { Text("Cancel") }
            }
        )
    }
    
    if (showReminderDialog) {
        var showTimePicker by remember { mutableStateOf(false) }
        var selectedTimeStr by remember { mutableStateOf("10:00") }
        var showDatePicker by remember { mutableStateOf(false) }
        var selectedDate by remember { mutableStateOf(Clock.System.todayIn(TimeZone.currentSystemDefault())) }

        if (showDatePicker) {
            val datePickerState = rememberDatePickerState(
                initialSelectedDateMillis = Clock.System.now().toEpochMilliseconds()
            )
            DatePickerDialog(
                onDismissRequest = { showDatePicker = false },
                colors = DatePickerDefaults.colors(containerColor = LocalAppColors.current.surface),
                confirmButton = {
                    TextButton(onClick = {
                        datePickerState.selectedDateMillis?.let { millis ->
                            val inst = kotlin.time.Instant.fromEpochMilliseconds(millis)
                            selectedDate = inst.toLocalDateTime(TimeZone.UTC).date
                        }
                        showDatePicker = false
                    }) { Text("OK", color = primaryColor) }
                },
                dismissButton = {
                    TextButton(onClick = { showDatePicker = false }) { Text("Cancel") }
                }
            ) {
                DatePicker(
                    state = datePickerState,
                    colors = DatePickerDefaults.colors(
                        selectedDayContainerColor = primaryColor,
                        todayDateBorderColor = primaryColor,
                        todayContentColor = primaryColor
                    )
                )
            }
        }

        if (showTimePicker) {
            val timePickerState = rememberTimePickerState(initialHour = 10, initialMinute = 0)
            AlertDialog(
                onDismissRequest = { showTimePicker = false },
                containerColor = LocalAppColors.current.surface,
                title = { Text("Select Time") },
                text = {
                    TimePicker(
                        state = timePickerState,
                        colors = TimePickerDefaults.colors(
                            selectorColor = primaryColor,
                            timeSelectorSelectedContainerColor = primaryColor.copy(alpha = 0.2f),
                            timeSelectorSelectedContentColor = primaryColor
                        )
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        selectedTimeStr = "${timePickerState.hour.toString().padStart(2, '0')}:${timePickerState.minute.toString().padStart(2, '0')}"
                        showTimePicker = false
                    }) { Text("OK", color = primaryColor) }
                },
                dismissButton = {
                    TextButton(onClick = { showTimePicker = false }) { Text("Cancel") }
                }
            )
        }

        AlertDialog(
            onDismissRequest = { showReminderDialog = false },
            containerColor = LocalAppColors.current.surface,
            title = { Text("Monthly Reminder") },
            text = {
                Column {
                    Text("Set a discreet monthly reminder to check?")
                    Spacer(Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { showDatePicker = true }.padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Start Date", style = MaterialTheme.typography.bodyLarge)
                        Text(selectedDate.toString(), color = primaryColor, fontWeight = FontWeight.Bold)
                    }
                    HorizontalDivider(color = Color.LightGray.copy(alpha = 0.3f))
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { showTimePicker = true }.padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Time", style = MaterialTheme.typography.bodyLarge)
                        Text(selectedTimeStr, color = primaryColor, fontWeight = FontWeight.Bold)
                    }
                    HorizontalDivider(color = Color.LightGray.copy(alpha = 0.3f))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    queries.insertReminder("Pregnancy Test", "Monthly", selectedTimeStr, selectedDate.toString(), true)
                    
                    val tz = TimeZone.currentSystemDefault()
                    val now = Clock.System.now()
                    val today = Clock.System.todayIn(tz)
                    
                    val timeParts = selectedTimeStr.split(":")
                    val targetTime = LocalTime(timeParts[0].toInt(), timeParts[1].toInt())
                    
                    var firstOccurrence = selectedDate
                    if (firstOccurrence < today) firstOccurrence = today
                    if (LocalDateTime(firstOccurrence, targetTime).toInstant(tz) < now) {
                        firstOccurrence = firstOccurrence.plus(1, DateTimeUnit.DAY)
                    }
                    val recurringDelay = (LocalDateTime(firstOccurrence, targetTime).toInstant(tz).toEpochMilliseconds() - now.toEpochMilliseconds()).coerceAtLeast(1000L)
                    
                    notificationScheduler.scheduleNotification(103, "Bloom", "🌸 Time for a quick check-in", recurringDelay)
                    showReminderDialog = false
                }) { Text("Yes, Remind Me", color = primaryColor) }
            },
            dismissButton = {
                TextButton(onClick = { showReminderDialog = false }) { Text("Cancel") }
            }
        )
    }
}
