package com.beginner_techies.chatbotapp.service;

import org.springframework.stereotype.Service;

import com.beginner_techies.chatbotapp.dto.EligibilityState;

@Service
public class EligibilityService {

    public static class Verdict {
        public enum Status { NEED_INFO, PRELIM_INELIGIBLE, BORDERLINE, PREQUALIFIED }
        public Status status;
        public java.util.List<String> reasons = new java.util.ArrayList<>();
        public Double dtiRatio;      // commitments / income
        public Double maxAllowedEmi; // 40–45% rule of income (after any penalties)
        public Double suggestedDtiCap; // the cap actually used
    }

    /** Main rule engine using your existing fields only. */
    public Verdict evaluateDetailed(EligibilityState s) {
        Verdict v = new Verdict();

        // ---- Required core slots
        if (s.getMonthlyIncome() == null) v.reasons.add("NEED_INCOME");
        if (s.getEmployerType() == null || s.getEmployerType().isBlank()) v.reasons.add("NEED_EMPLOYER_TYPE");
        if (s.getServiceMonths() == null) v.reasons.add("NEED_SERVICE_MONTHS");

        if (!v.reasons.isEmpty()) {
            v.status = Verdict.Status.NEED_INFO;
            return v;
        }

        // ---- Normalize inputs
        double income = Math.max(0, s.getMonthlyIncome());
        String emp = normalizeEmployerType(s.getEmployerType());
        int svc = Math.max(0, s.getServiceMonths());
        boolean hasLoans = s.getHasExistingLoans() != null && s.getHasExistingLoans();
        double other = s.getOtherObligationsMonthly() == null ? 0.0 : Math.max(0, s.getOtherObligationsMonthly());
        boolean isSaudi = isSaudi(s.getNationality());

        // ---- Policy thresholds
        double minIncome;
        int minService;
        // Base DTI cap (commitments/income). SAMA Responsible Lending caps the
        // debt burden ratio at 33.33% of gross salary for employees (25% for
        // retirees) — internal policy may only tighten below that, never exceed.
        final double SAMA_DBR_CAP = 1.0 / 3.0;
        double baseDtiCap = switch (emp) {
            case "government",
                 "private"    -> SAMA_DBR_CAP;
            case "contract",
                 "self-employed" -> 0.30;
            default           -> SAMA_DBR_CAP;
        };
        // Nationality tightening
        if (!isSaudi) baseDtiCap -= 0.05;

        // Income & service thresholds by employer + nationality
        if (emp.equals("government")) {
            minIncome = isSaudi ? 4000 : 6000;
            minService = 6;
        } else if (emp.equals("private")) {
            minIncome = isSaudi ? 5000 : 7000;
            minService = 12;
        } else if (emp.equals("contract")) {
            minIncome = isSaudi ? 7000 : 9000;
            minService = 18;
        } else { // self-employed or unknown
            minIncome = isSaudi ? 7000 : 9000;
            minService = 24;
        }

        // Existing loans penalty on DTI cap (tighter)
        double dtiCap = baseDtiCap - (hasLoans ? 0.05 : 0.0);
        dtiCap = clamp(dtiCap, 0.20, 1.0 / 3.0); // never above the SAMA 33.33% cap

        // ---- Deterministic checks
        if (income < minIncome) v.reasons.add("LOW_INCOME");
        if (svc < minService) v.reasons.add("LOW_SERVICE_MONTHS");

        double dti = (income <= 0) ? 1.0 : (other / income);
        v.dtiRatio = round2(dti * 100.0) / 100.0; // keep as fraction internally; round display outside
        v.suggestedDtiCap = dtiCap;

        if (dti > dtiCap) v.reasons.add("DTI_ABOVE_CAP");

        // ---- Verdict logic
        if (!v.reasons.isEmpty()) {
            // If only one reason and close to the threshold → borderline
            if (v.reasons.size() == 1 && (
                    nearPct(income, minIncome, 0.08) ||
                    nearInt(svc, minService, 2) ||
                    nearPct(dti, dtiCap, 0.08)
            )) {
                v.status = Verdict.Status.BORDERLINE;
            } else {
                v.status = Verdict.Status.PRELIM_INELIGIBLE;
            }
        } else {
            v.status = Verdict.Status.PREQUALIFIED;
        }

        // Max affordable EMI (guideline) = dtiCap * income  (if you want to show it)
        v.maxAllowedEmi = round2(dtiCap * income);
        return v;
    }

    // ---------- helpers ----------
    private static String normalizeEmployerType(String raw) {
        if (raw == null) return "private";
        String r = raw.trim().toLowerCase();
        if (r.contains("gov") || r.contains("حكوم")) return "government";
        if (r.contains("priv") || r.contains("خاص")) return "private";
        if (r.contains("contract") || r.contains("متعاقد")) return "contract";
        if (r.contains("self") || r.contains("حر")) return "self-employed";
        return r; // already normalized?
    }

    private static boolean isSaudi(String nationality) {
        if (nationality == null) return true; // default neutral
        String n = nationality.trim().toLowerCase();
        // "non-saudi" CONTAINS "saudi" — check the negative forms first, or
        // expats get evaluated with the looser Saudi thresholds
        boolean negative = n.contains("non") || n.contains("غير");
        return !negative && (n.contains("saudi") || n.contains("سعود"));
    }

    private static double clamp(double x, double lo, double hi) {
        return Math.max(lo, Math.min(hi, x));
    }

    private static boolean nearPct(double x, double target, double tol) {
        if (target == 0) return Math.abs(x) <= tol;
        return Math.abs(x - target) / Math.abs(target) <= tol;
    }
    private static boolean nearInt(int x, int target, int tol) {
        return Math.abs(x - target) <= tol;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}