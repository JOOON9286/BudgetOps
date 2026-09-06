package com.example.backend.expense;

import com.example.backend.expense.entity.Expense;
import com.example.backend.expense.entity.ExpenseStatus;
import com.example.backend.member.entity.User;
import com.example.backend.team.entity.Team;
import com.example.backend.team.entity.TeamType;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

// @DataJpaTest 는 기본적으로 테스트 메서드 전체를 하나의 트랜잭션으로 감싸고 끝나면 롤백한다.
// 이 테스트는 여러 스레드가 "각자의 트랜잭션"에서 같은 row 를 읽고 커밋해야 하므로,
// 메서드 레벨 트랜잭션을 끄고(Propagation.NOT_SUPPORTED) 픽스처를 명시적으로 커밋한 뒤
// 동시성 검증을 한다. 커밋한 데이터는 롤백되지 않으므로 @AfterEach 에서 직접 정리한다.
@DataJpaTest
class ExpenseConcurrencyTest {

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate txTemplate;

    @BeforeEach
    void setUp() {
        txTemplate = new TransactionTemplate(transactionManager);
    }

    @AfterEach
    void tearDown() {
        txTemplate.executeWithoutResult(status -> {
            entityManager.createQuery("delete from Expense").executeUpdate();
            entityManager.createQuery("delete from Team").executeUpdate();
            entityManager.createQuery("delete from User").executeUpdate();
        });
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void 같은_지출에_승인과_반려가_동시에_요청되면_한쪽만_성공한다() throws Exception {
        AtomicReference<Long> expenseIdRef = new AtomicReference<>();
        AtomicReference<Long> manager1IdRef = new AtomicReference<>();
        AtomicReference<Long> manager2IdRef = new AtomicReference<>();

        // 픽스처를 별도 트랜잭션에서 커밋해야 아래 스레드들이 각자의 커넥션에서 볼 수 있다.
        txTemplate.executeWithoutResult(status -> {
            User applicant = User.create("password", "applicant@test.com", "신청자", LocalDate.of(2000, 1, 1), "010-1111-1111");
            User manager1 = User.create("password", "manager1@test.com", "관리자1", LocalDate.of(1999, 1, 1), "010-2222-2222");
            User manager2 = User.create("password", "manager2@test.com", "관리자2", LocalDate.of(1998, 1, 1), "010-3333-3333");
            entityManager.persist(applicant);
            entityManager.persist(manager1);
            entityManager.persist(manager2);

            Team team = Team.builder()
                    .name("동시성 테스트 팀")
                    .description("optimistic locking test")
                    .teamType(TeamType.스터디)
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

            expenseIdRef.set(expense.getId());
            manager1IdRef.set(manager1.getId());
            manager2IdRef.set(manager2.getId());
        });

        Long expenseId = expenseIdRef.get();
        Long manager1Id = manager1IdRef.get();
        Long manager2Id = manager2IdRef.get();

        CountDownLatch loaded = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> approveError = new AtomicReference<>();
        AtomicReference<Throwable> rejectError = new AtomicReference<>();

        Thread approveThread = new Thread(() -> runInTransaction(() -> {
            Expense target = entityManager.find(Expense.class, expenseId);
            loaded.countDown();
            await(start);
            target.approve(entityManager.getReference(User.class, manager1Id));
            // flush 를 명시하지 않고 커밋 시점에 flush 되게 두어야
            // JpaTransactionManager 가 예외를 ObjectOptimisticLockingFailureException 으로 변환한다.
        }, approveError));

        Thread rejectThread = new Thread(() -> runInTransaction(() -> {
            Expense target = entityManager.find(Expense.class, expenseId);
            loaded.countDown();
            await(start);
            target.reject(entityManager.getReference(User.class, manager2Id), "동시 처리 테스트");
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
