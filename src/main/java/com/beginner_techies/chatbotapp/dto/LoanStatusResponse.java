package com.beginner_techies.chatbotapp.dto;

public class LoanStatusResponse {
    public enum Status {
        SUBMITTED, UNDER_REVIEW, APPROVED, FUNDED, REJECTED, UNKNOWN
    }
    private Status status;
    private String reason; // optional for rejected or notes

    public LoanStatusResponse() {}
    public LoanStatusResponse(Status status, String reason) {
        this.status = status; this.reason = reason;
    }

    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    // Convenience
    public String toEnglish() {
        return switch (status) {
            case SUBMITTED    -> "Submitted";
            case UNDER_REVIEW -> "Under Review";
            case APPROVED     -> "Approved";
            case FUNDED       -> "Funded";
            case REJECTED     -> (reason == null || reason.isBlank() ? "Rejected" : "Rejected — " + reason);
            default           -> "Unknown";
        };
    }
    public String toArabic() {
        return switch (status) {
            case SUBMITTED    -> "تم الإرسال";
            case UNDER_REVIEW -> "قيد المراجعة";
            case APPROVED     -> "تمت الموافقة";
            case FUNDED       -> "تم التمويل";
            case REJECTED     -> (reason == null || reason.isBlank() ? "مرفوض" : "مرفوض — " + reason);
            default           -> "غير معروف";
        };
    }
}