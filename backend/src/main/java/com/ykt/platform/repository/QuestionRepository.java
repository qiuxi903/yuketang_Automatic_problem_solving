package com.ykt.platform.repository;

import com.ykt.platform.entity.Question;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface QuestionRepository extends JpaRepository<Question, Long> {

    long countByPaperId(Long paperId);

    List<Question> findByPaperIdOrderBySeqAsc(Long paperId);
}
