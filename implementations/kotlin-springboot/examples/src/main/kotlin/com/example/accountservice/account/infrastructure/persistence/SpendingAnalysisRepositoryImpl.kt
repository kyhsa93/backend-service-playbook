package com.example.accountservice.account.infrastructure.persistence

import com.example.accountservice.account.application.query.SpendingAnalysisFindQuery
import com.example.accountservice.account.application.query.SpendingAnalysisQuery
import com.example.accountservice.account.domain.SpendingAnalysis
import com.example.accountservice.account.domain.SpendingAnalysisRepository
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

@Repository
class SpendingAnalysisRepositoryImpl(
    private val jpaRepository: SpendingAnalysisJpaRepository,
) : SpendingAnalysisRepository,
    SpendingAnalysisQuery {
    @Transactional
    override fun saveAnalysis(analysis: SpendingAnalysis) {
        jpaRepository.save(SpendingAnalysisMapper.toNewEntity(analysis))
    }

    override fun hasAnalysis(
        accountId: String,
        analysisMonth: String,
    ): Boolean = jpaRepository.existsByAccountIdAndAnalysisMonth(accountId, analysisMonth)

    override fun findAnalyses(query: SpendingAnalysisFindQuery): List<SpendingAnalysis> =
        jpaRepository
            .findByAccountIdAndAnalysisMonth(query.accountId, query.analysisMonth)
            ?.let { listOf(SpendingAnalysisMapper.toDomain(it)) }
            ?: emptyList()
}
