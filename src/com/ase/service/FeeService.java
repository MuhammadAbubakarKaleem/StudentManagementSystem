package com.ase.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import com.ase.model.Student;

public class FeeService {
    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final List<Student> students;
    private final List<Voucher> vouchers = new ArrayList<>();
    private int nextVoucherNumber = 1;

    public FeeService(List<Student> students) {
        this.students = Objects.requireNonNull(students, "Students cannot be null.");
    }

    public Voucher generateVoucher(String studentId, BigDecimal amount) {
        Student student = findStudentById(studentId);
        if (student == null) {
            throw new IllegalArgumentException("Student not found.");
        }

        BigDecimal validatedAmount = validateAmount(amount);
        String voucherId = String.format("FV-%04d", nextVoucherNumber++);
        Voucher voucher = new Voucher(voucherId, student.id, validatedAmount, LocalDateTime.now());
        vouchers.add(voucher);
        return voucher;
    }

    public Payment recordPayment(String voucherId, BigDecimal amount) {
        Voucher voucher = findVoucherById(voucherId);
        if (voucher == null) {
            throw new IllegalArgumentException("Voucher not found.");
        }

        BigDecimal validatedAmount = validateAmount(amount);
        if (validatedAmount.compareTo(voucher.getBalance()) > 0) {
            throw new IllegalArgumentException("Payment cannot exceed the voucher balance of "
                    + voucher.getBalance().toPlainString() + ".");
        }

        Payment payment = new Payment(validatedAmount, LocalDateTime.now());
        voucher.addPayment(payment);
        return payment;
    }

    public Student findStudentById(String studentId) {
        if (studentId == null || studentId.trim().isEmpty()) {
            return null;
        }
        for (Student student : students) {
            if (student != null && student.id != null
                    && student.id.equalsIgnoreCase(studentId.trim())) {
                return student;
            }
        }
        return null;
    }

    public List<Voucher> getFeeStatement(String studentId) {
        Student student = findStudentById(studentId);
        if (student == null) {
            throw new IllegalArgumentException("Student not found.");
        }

        List<Voucher> statement = new ArrayList<>();
        for (Voucher voucher : vouchers) {
            if (voucher.getStudentId().equalsIgnoreCase(student.id)) {
                statement.add(voucher);
            }
        }
        return Collections.unmodifiableList(statement);
    }

    public List<Defaulter> getDefaulters() {
        List<Defaulter> defaulters = new ArrayList<>();
        for (Student student : students) {
            if (student == null || student.id == null) {
                continue;
            }
            BigDecimal outstanding = BigDecimal.ZERO.setScale(2);
            int outstandingVoucherCount = 0;
            for (Voucher voucher : vouchers) {
                if (voucher.getStudentId().equalsIgnoreCase(student.id)
                        && voucher.getBalance().signum() > 0) {
                    outstanding = outstanding.add(voucher.getBalance());
                    outstandingVoucherCount++;
                }
            }
            if (outstanding.signum() > 0) {
                defaulters.add(new Defaulter(student.id, student.n, outstanding, outstandingVoucherCount));
            }
        }
        return Collections.unmodifiableList(defaulters);
    }

    private Voucher findVoucherById(String voucherId) {
        if (voucherId == null || voucherId.trim().isEmpty()) {
            return null;
        }
        for (Voucher voucher : vouchers) {
            if (voucher.getId().equalsIgnoreCase(voucherId.trim())) {
                return voucher;
            }
        }
        return null;
    }

    private BigDecimal validateAmount(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("Amount must be greater than zero.");
        }
        if (amount.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException("Amount can have no more than two decimal places.");
        }
        try {
            return amount.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("Amount can have no more than two decimal places.");
        }
    }

    public static final class Voucher {
        private final String id;
        private final String studentId;
        private final BigDecimal amount;
        private final LocalDateTime issuedAt;
        private final List<Payment> payments = new ArrayList<>();

        private Voucher(String id, String studentId, BigDecimal amount, LocalDateTime issuedAt) {
            this.id = id;
            this.studentId = studentId;
            this.amount = amount;
            this.issuedAt = issuedAt;
        }

        public String getId() {
            return id;
        }

        public String getStudentId() {
            return studentId;
        }

        public BigDecimal getAmount() {
            return amount;
        }

        public String getIssuedAt() {
            return issuedAt.format(DATE_FORMAT);
        }

        public BigDecimal getPaidAmount() {
            BigDecimal paidAmount = BigDecimal.ZERO.setScale(2);
            for (Payment payment : payments) {
                paidAmount = paidAmount.add(payment.getAmount());
            }
            return paidAmount;
        }

        public BigDecimal getBalance() {
            return amount.subtract(getPaidAmount());
        }

        public List<Payment> getPayments() {
            return Collections.unmodifiableList(payments);
        }

        private void addPayment(Payment payment) {
            payments.add(payment);
        }
    }

    public static final class Payment {
        private final BigDecimal amount;
        private final LocalDateTime paidAt;

        private Payment(BigDecimal amount, LocalDateTime paidAt) {
            this.amount = amount;
            this.paidAt = paidAt;
        }

        public BigDecimal getAmount() {
            return amount;
        }

        public String getPaidAt() {
            return paidAt.format(DATE_FORMAT);
        }
    }

    public static final class Defaulter {
        private final String studentId;
        private final String studentName;
        private final BigDecimal outstandingAmount;
        private final int outstandingVoucherCount;

        private Defaulter(String studentId, String studentName, BigDecimal outstandingAmount,
                int outstandingVoucherCount) {
            this.studentId = studentId;
            this.studentName = studentName;
            this.outstandingAmount = outstandingAmount;
            this.outstandingVoucherCount = outstandingVoucherCount;
        }

        public String getStudentId() {
            return studentId;
        }

        public String getStudentName() {
            return studentName;
        }

        public BigDecimal getOutstandingAmount() {
            return outstandingAmount;
        }

        public int getOutstandingVoucherCount() {
            return outstandingVoucherCount;
        }
    }
}
