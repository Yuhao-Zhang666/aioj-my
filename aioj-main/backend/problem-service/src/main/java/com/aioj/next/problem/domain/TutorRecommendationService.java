package com.aioj.next.problem.domain;
import com.aioj.next.contract.problem.Difficulty;

import com.aioj.next.common.security.SecuritySupport;
import com.aioj.next.contract.problem.TutorProblemResponse;
import com.aioj.next.contract.problem.TutorRecommendationResponse;
import com.aioj.next.contract.submission.SubmissionStatus;
import com.aioj.next.problem.persistence.entity.ProblemEntity;
import com.aioj.next.problem.persistence.entity.SubmissionEntity;
import com.aioj.next.problem.persistence.mapper.ProblemMapper;
import com.aioj.next.problem.persistence.mapper.SubmissionMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;
import com.aioj.next.contract.problem.Difficulty;
import java.util.HashSet;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class TutorRecommendationService {
    private static final int DEFAULT_LIMIT = 10;
    private static final int MAX_LIMIT = 50;
    private static final Set<SubmissionStatus> KNOWLEDGE_FAILURE_STATUSES = Set.of(
        SubmissionStatus.WRONG_ANSWER,
        SubmissionStatus.RUNTIME_ERROR,
        SubmissionStatus.TIME_LIMIT_EXCEEDED,
        SubmissionStatus.MEMORY_LIMIT_EXCEEDED,
        SubmissionStatus.OUTPUT_LIMIT_EXCEEDED
);

    private final ProblemMapper problemMapper;
    private final SubmissionMapper submissionMapper;
    private final ProblemCatalog problemCatalog;

    public TutorRecommendationService(ProblemMapper problemMapper, SubmissionMapper submissionMapper,
                                      ProblemCatalog problemCatalog) {
        this.problemMapper = problemMapper;
        this.submissionMapper = submissionMapper;
        this.problemCatalog = problemCatalog;
    }

    public List<TutorRecommendationResponse> recommend(Integer requestedLimit) {
        Long userId = SecuritySupport.currentUserId();
        int limit = normalizeLimit(requestedLimit);
        List<SubmissionEntity> submissions = submissionMapper.selectList(new LambdaQueryWrapper<SubmissionEntity>()
                .eq(SubmissionEntity::getUserId, userId)
                .isNull(SubmissionEntity::getContestId)
                .isNull(SubmissionEntity::getContestRunId)
                .isNull(SubmissionEntity::getContestProblemId)
                .orderByDesc(SubmissionEntity::getCreatedAt)
                .orderByDesc(SubmissionEntity::getId));

        Map<Long, ProblemHistory> history = new HashMap<>();
        for (SubmissionEntity submission : submissions) {
            if (submission.getProblemId() == null) {
                continue;
            }
            ProblemHistory current = history.computeIfAbsent(submission.getProblemId(), ignored -> new ProblemHistory());
            current.attempts++;

            SubmissionStatus status = submission.getStatus();
            if (status == SubmissionStatus.ACCEPTED) {
                current.accepted = true;
            } else if (status != null && KNOWLEDGE_FAILURE_STATUSES.contains(status)) {
                current.failureCount++;
                if (current.firstFailure == null) {
                    current.firstFailure = status;
                }
            }
        }

        List<ProblemEntity> publicProblems = problemMapper.selectList(new LambdaQueryWrapper<ProblemEntity>()
                .eq(ProblemEntity::getDeleted, false)
                .isNull(ProblemEntity::getDeletedAt)
                .isNull(ProblemEntity::getArchivedAt)
                .eq(ProblemEntity::getVisibility, com.aioj.next.contract.problem.ProblemVisibility.PUBLIC));
        Map<String, Integer> weakTagScores = new HashMap<>();

        for (ProblemEntity problem : publicProblems) {
            ProblemHistory item = history.get(problem.getId());
            if (item == null || item.accepted || item.failureCount == 0) {
                continue;
            }

            for (String tag : problemCatalog.tagsOf(problem)) {
                if (tag == null || tag.isBlank()) {
                    continue;
                }
                weakTagScores.merge(tag.trim(), item.failureCount, Integer::sum);
            }
        }
        List<ScoredProblem> ranked = new ArrayList<>();
        for (ProblemEntity problem : publicProblems) {
            ProblemHistory item = history.get(problem.getId());
            if (item != null && item.accepted) {
                continue;
            }
            List<String> matchingTags = problemCatalog.tagsOf(problem).stream()
                .map(String::trim)
                .filter(tag -> !tag.isEmpty())
                .filter(weakTagScores::containsKey)
                .toList();

            int tagScore = matchingTags.stream()
                    .mapToInt(tag -> Math.min(weakTagScores.get(tag), 5))
                    .sum() * 10;

            double score = (item == null
                    ? 100.0
                    : 70.0
                            - Math.min(item.attempts, 10) * 3.0
                            + Math.min(item.failureCount, 5) * 2.0)
                    + tagScore;
            String reason;
            if (item != null && item.failureCount > 0) {
                reason = "曾提交但尚未通过，适合针对性复习";
            } else if (!matchingTags.isEmpty()) {
                reason = "你最近在「" + matchingTags.get(0) + "」知识点上有未通过记录";
            } else {
                reason = "尚未提交过，适合作为新的练习题";
            }
            ranked.add(new ScoredProblem(problem, score, reason));
        }
        ranked.sort(Comparator.comparingDouble(ScoredProblem::score).reversed()
                .thenComparing(item -> item.problem().getCreatedAt(), Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(item -> item.problem().getId(), Comparator.nullsLast(Comparator.reverseOrder())));
        List<ScoredProblem> selected = history.isEmpty()
                ? selectColdStart(ranked, limit)
                : ranked.stream().limit(limit).toList();
        return selected.stream().map(item -> new TutorRecommendationResponse(
                problemCatalog.toTutorResponse(item.problem()),
                BigDecimal.valueOf(item.score()).setScale(2, java.math.RoundingMode.HALF_UP),
                item.reason())).toList();
    }

    private List<ScoredProblem> selectColdStart(List<ScoredProblem> ranked, int limit) {
        List<Difficulty> preferredDifficulties = List.of(
                Difficulty.EASY,
                Difficulty.MEDIUM,
                Difficulty.EASY,
                Difficulty.MEDIUM,
                Difficulty.HARD,
                Difficulty.CHALLENGE
        );

        List<ScoredProblem> selected = new ArrayList<>();
        Set<Long> selectedIds = new HashSet<>();

        for (Difficulty target : preferredDifficulties) {
            if (selected.size() >= limit) {
                break;
            }

            for (ScoredProblem candidate : ranked) {
                if (selectedIds.contains(candidate.problem().getId())) {
                    continue;
                }
                if (candidate.problem().getDifficulty() != target) {
                    continue;
                }

                selected.add(candidate);
                selectedIds.add(candidate.problem().getId());
                break;
            }
        }

        // 某种难度题目不足时，用剩余公开题补齐
        for (ScoredProblem candidate : ranked) {
            if (selected.size() >= limit) {
                break;
            }
            if (selectedIds.add(candidate.problem().getId())) {
                selected.add(candidate);
            }
        }

        return selected;
    }

    private int normalizeLimit(Integer requestedLimit) {
        if (requestedLimit == null || requestedLimit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(requestedLimit, MAX_LIMIT);
    }

    private static final class ProblemHistory {
        private int attempts;
        private int failureCount;
        private boolean accepted;
        private SubmissionStatus firstFailure;
    }

    private record ScoredProblem(ProblemEntity problem, double score, String reason) {
    }
}
