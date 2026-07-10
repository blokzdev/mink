export const meta = {
  name: 'mink-review',
  description: 'Adversarial correctness review of the Mink codebase, verified per finding',
  phases: [
    { title: 'Review', detail: 'subsystem reviewers surface correctness findings' },
    { title: 'Verify', detail: 'each finding is adversarially checked' },
  ],
}

const ROOT = '/home/user/mink'

const FIND_SCHEMA = {
  type: 'object',
  properties: {
    findings: {
      type: 'array',
      items: {
        type: 'object',
        properties: {
          severity: { type: 'string', enum: ['critical', 'high', 'medium', 'low'] },
          file: { type: 'string' },
          line: { type: 'integer' },
          summary: { type: 'string' },
          failureScenario: { type: 'string', description: 'concrete inputs/state -> wrong behavior or crash' },
          suggestedFix: { type: 'string' },
        },
        required: ['severity', 'file', 'summary', 'failureScenario'],
      },
    },
  },
  required: ['findings'],
}

const VERDICT_SCHEMA = {
  type: 'object',
  properties: {
    isReal: { type: 'boolean', description: 'true only if this is a genuine defect that will actually misbehave' },
    confidence: { type: 'string', enum: ['high', 'medium', 'low'] },
    reasoning: { type: 'string' },
    correctedFix: { type: 'string' },
  },
  required: ['isReal', 'confidence', 'reasoning'],
}

const REVIEW_PREAMBLE = `You are a meticulous senior Android reviewer auditing the Mink app at ${ROOT}
(Kotlin, Jetpack Compose, coroutines, minSdk 26). The code compiles. Find real CORRECTNESS
bugs a user would hit: crashes, wrong behavior, coroutine/threading mistakes, leaked
resources/receivers, StateFlow/recomposition errors, permission mishandling, lifecycle bugs,
main-thread violations, incorrect Android API use, null/format crashes, resource/manifest
mismatches. Read the actual files. Be concrete: for each finding give the file, line, and a
specific failure scenario (inputs/state -> what breaks). Prefer a few high-certainty findings
over speculation. Ignore pure style. Rank by severity.`

function reviewer(area, prompt) {
  return agent(`${REVIEW_PREAMBLE}\n\n=== YOUR AREA ===\n${prompt}`, {
    label: `review:${area}`,
    phase: 'Review',
    schema: FIND_SCHEMA,
    model: 'opus',
    effort: 'high',
    agentType: 'general-purpose',
  })
}

const AREAS = [
  ['signals', `The 30 signal providers in ${ROOT}/app/src/main/java/com/mink/signals plus
PassiveFormat/PermissionedFormat/AppTaxonomy. Check: every provider is exception-safe and
never throws; SDK-version guards are correct; permissioned providers actually check the
grant; live providers (Battery, Location, Activity) build correct Flows with proper cleanup
(awaitClose/unregister); GpuProvider's EGL context is created and torn down safely off the
main thread; WebViewFingerprintProvider runs the WebView on the main thread with a real
timeout and no leak; no blocking I/O without a dispatcher; formats don't crash on odd values.`],
  ['guardian', `The guardian in ${ROOT}/app/src/main/java/com/mink/guardian and /llm plus the
native ${ROOT}/app/src/main/cpp. Check: GuardianController threading and StateFlow updates;
model load/unload lifecycle; chat() streaming correctness and think/no-think parsing in
MiniCpmChatFormat; LlamaBridge degrades safely when libmink_llm.so is absent
(isAvailable=false, no UnsatisfiedLinkError crash); RulesEngine is deterministic and always
available; GuardianAnalyzer snapshot diff logic; GuardianStore persistence (no main-thread
I/O, safe JSON); GuardianService foreground-service start correctness (startForeground called
in time, notification channel created, FGS type); WorkManager scheduling. The JNI signatures
in mink_llm.cpp must match the external funs in LlamaBridge.kt exactly.`],
  ['companion', `The companion in ${ROOT}/app/src/main/java/com/mink/companion. Check:
CompanionOverlayService adds/removes the WindowManager view correctly and cleans it up in
onDestroy (no leaked window); TYPE_APPLICATION_OVERLAY usage; the ComposeView is wired with a
valid Lifecycle/SavedStateRegistry/ViewModelStore owner so Compose renders in the overlay
without crashing; drag/snap math in SnapMath is correct; foreground-service start + channel;
CompanionController overlay-permission checks and StateFlow correctness; MinkSpriteArt grids
are rectangular and indices are in-bounds so MinkSprite never draws out of range.`],
  ['ui', `The UI in ${ROOT}/app/src/main/java/com/mink/ui (screens, nav, components, vm,
export, MinkRoot, OnboardingStore). Check: state is collected with lifecycle-aware collectors;
no work on the main thread in composition; navigation routes and argument parsing are correct
(category/{id} decodes to a real SignalCategory); the permission launcher bridge attributes
results to the right kind/category; ViewModels use the right scope and don't leak; LazyColumn
keys are unique; ReportBuilder produces valid output and ExportScreen's FileProvider share
uses the declared authority; nullable services.guardian/companion are handled on every screen;
recomposition doesn't restart infinite effects.`],
  ['integration', `Cross-cutting concerns across ${ROOT}. Check: ServiceWiring +
MinkApplication build the graph without leaking the app scope; the guardian/companion factory
wiring matches constructor signatures; AndroidManifest declares every component, permission,
service foregroundServiceType, and the FileProvider authority actually used in code; the
ProviderRegistry covers all 30 categories with matching class names; no ANRs from synchronous
work in Application.onCreate; DataStore instances are singletons (not created per call);
StateFlow back-pressure and unbounded list growth; any obvious memory leak (Context held
statically, e.g. GuardianServiceHost).`],
]

phase('Review')
const reviewed = await parallel(AREAS.map(([area, prompt]) => () => reviewer(area, prompt)))
const findings = reviewed.filter(Boolean).flatMap((r, i) =>
  (r.findings || []).map(f => ({ ...f, area: AREAS[i][0] })))
log(`Surfaced ${findings.length} candidate findings; verifying each`)

phase('Verify')
const verified = await parallel(findings.map(f => () =>
  agent(`Adversarially verify this claimed defect in the Mink Android app. Read the actual
file(s) at ${ROOT} and the code around it. Decide if it is a REAL defect that will actually
misbehave at runtime, or a false positive (the code already handles it, the API works as the
author assumed, the path is unreachable, or it is mere style). Default to isReal=false unless
you can name the concrete failure.

FINDING (${f.severity}, ${f.area}) ${f.file}:${f.line || '?'}
${f.summary}
Failure scenario claimed: ${f.failureScenario}
Suggested fix: ${f.suggestedFix || '(none)'}`, {
    label: `verify:${f.area}:${(f.file || '').split('/').pop()}`,
    phase: 'Verify',
    schema: VERDICT_SCHEMA,
    model: 'opus',
    effort: 'high',
    agentType: 'general-purpose',
  }).then(v => ({ finding: f, verdict: v })).catch(() => null)))

const confirmed = verified.filter(Boolean).filter(v => v.verdict?.isReal)
const order = { critical: 0, high: 1, medium: 2, low: 3 }
confirmed.sort((a, b) => (order[a.finding.severity] ?? 9) - (order[b.finding.severity] ?? 9))

return {
  candidateCount: findings.length,
  confirmedCount: confirmed.length,
  confirmed: confirmed.map(v => ({
    severity: v.finding.severity,
    area: v.finding.area,
    file: v.finding.file,
    line: v.finding.line,
    summary: v.finding.summary,
    failureScenario: v.finding.failureScenario,
    fix: v.verdict.correctedFix || v.finding.suggestedFix,
    confidence: v.verdict.confidence,
    reasoning: v.verdict.reasoning,
  })),
}
