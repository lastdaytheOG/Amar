\# Phase 5 — Apps to Validate (discovered failures from Phase 4 testing)



\## Confirmed broken (need investigation)

\- Rapido (com.rapido.passenger) — search does not work

\- Ola (com.olacabs.customer) — search does not work

\- Uber (com.ubercab) — search does not work

\- Myntra (com.myntra.android) — search blocked by 1-2s animation popup

\- Google Pay (com.google.android.apps.nbu.paisa.user) — search does not work

\- Discord (com.discord) — search does not work



\## Hypothesis (NOT verified — Phase 5 will diagnose):

\- Rapido/Ola/Uber: search uses MAP-based location autocomplete, not standard search bar

\- Myntra: animation overlay intercepts input before editable field is ready

\- Google Pay: requires biometric/auth before search becomes accessible

\- Discord: search likely needs multi-step navigation (channel → search), not direct



\## Phase 5 method

For each app:

1\. Run workflow on device

2\. Pull replay JSON

3\. Run analyzer to see where it fails

4\. Classify failure (animation\_overlay, requires\_auth, map\_overlay, multi\_step\_nav, etc.)

5\. Decide if it's a generalized weakness or per-app patch



\## Generalized fixes to derive from these

\- Overlay animation detector (settle wait beyond UiReadinessWaiter)

\- Multi-step navigation (search inside conversation lists)

\- Map-input affordances (different from text-input affordances)

