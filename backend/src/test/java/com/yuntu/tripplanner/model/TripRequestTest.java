package com.yuntu.tripplanner.model;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TripRequest 跨字段日期校验测试。
 */
class TripRequestTest {

    private static Validator validator;

    @BeforeAll
    static void setUp() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    private TripRequest request(LocalDate start, LocalDate end) {
        TripRequest req = new TripRequest();
        req.setDestination("成都");
        req.setStartDate(start);
        req.setEndDate(end);
        return req;
    }

    @Test
    void validDatesPass() {
        Set<ConstraintViolation<TripRequest>> violations =
                validator.validate(request(LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 3)));
        assertTrue(violations.isEmpty());
    }

    @Test
    void endBeforeStartFails() {
        Set<ConstraintViolation<TripRequest>> violations =
                validator.validate(request(LocalDate.of(2026, 5, 5), LocalDate.of(2026, 5, 1)));
        assertTrue(violations.stream().anyMatch(v -> "dateRangeValid".equals(v.getPropertyPath().toString())));
    }

    @Test
    void tripLongerThan31DaysFails() {
        Set<ConstraintViolation<TripRequest>> violations =
                validator.validate(request(LocalDate.of(2026, 5, 1), LocalDate.of(2026, 6, 20)));
        assertTrue(violations.stream().anyMatch(v -> "tripLengthValid".equals(v.getPropertyPath().toString())));
    }
}
