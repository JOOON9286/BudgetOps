package com.example.backend.expense;

import com.example.backend.expense.entity.Expense;
import com.example.backend.expense.entity.ExpenseStatus;
import com.example.backend.member.entity.User;
import com.example.backend.team.entity.Team;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class ExpenseConcurrencyTest {

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void 같은_지출에_승인과_반려가_동시에_요청되면_한쪽만_성공한다() throws Exception {
        User applicant = User.create("password", "applicant@test.com", "신청자", LocalDate.of(2000, 1, 1), "010-1111-1111");
        User manager1 = User.create("password", "manager1@test.com", "관리자1", LocalDate.of(1999, 1, 1), "010-2222-2222");
        User manager2 = User.create("password", "manager2@test.com", "관리자2", LocalDate.of(1998, 1, 1), "010-3333-3333");
        entityManager.persist(applicant);
        entityManager.persist(manager1);
        entityManager.persist(manager2);

        Team team = Team.builder()
                .name("동시성 테스트 팀")
                .description("optimistic locking test")
                .teamType(com.example.backend.team.entity.TeamType.스터디)
                .admin(manager1)
                .build();
        entityManager.persist(team);

        Expense expense = Expense.builder()
                .team(team)
                .user(applicant)
                .title("테스트 지출")
                .amount(10000L)
                .description("동시성 테스트")
                .expenseDate(LocalDate.now())
                .build();
        entityManager.persist(expense);
        entityManager.flush();
        Long expenseId = expense.getId();
        entityManager.clear();

        CountDownLatch loaded = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> approveError = new AtomicReference<>();
        AtomicReference<Throwable> rejectError = new AtomicReference<>();

        Thread approveThread = new Thread(() -> runInTransaction(() -> {
            Expense target = entityManager.find(Expense.class, expenseId);
            loaded.countDown();
            await(start);
            target.approve(manager1);
            entityManager.flush();
        }, approveError));

        Thread rejectThread = new Thread(() -> runInTransaction(() -> {
            Expense target = entityManager.find(Expense.class, expenseId);
            loaded.countDown();
            await(start);
            target.reject(manager2, "동시 처리 테스트");
            entityManager.flush();
        }, rejectError));

        approveThread.start();
        rejectThread.start();

        assertThat(loaded.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        approveThread.join(5000);
        rejectThread.join(5000);

        Throwable first = approveError.get();
        Throwable second = rejectError.get();
        assertThat(first == null ^ second == null).isTrue();

        Throwable failure = first != null ? first : second;
        assertThat(failure).isInstanceOf(ObjectOptimisticLockingFailureException.class);

        Expense finalExpense = entityManager.find(Expense.class, expenseId);
        assertThat(finalExpense.getStatus()).isIn(ExpenseStatus.APPROVED, ExpenseStatus.REJECTED);
        assertThat(finalExpense.getVersion()).isEqualTo(1);
    }

    private void runInTransaction(Runnable action, AtomicReference<Throwable> error) {
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> action.run());
        } catch (Throwable throwable) {
            error.set(throwable);
        }
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("동시성 테스트 시작 대기 시간 초과");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("동시성 테스트가 중단되었습니다.", e);
        }
    }
}
