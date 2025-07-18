/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.plan;

import org.elasticsearch.common.io.stream.NamedWriteableRegistry;
import org.elasticsearch.xpack.esql.plan.logical.Aggregate;
import org.elasticsearch.xpack.esql.plan.logical.Dissect;
import org.elasticsearch.xpack.esql.plan.logical.Enrich;
import org.elasticsearch.xpack.esql.plan.logical.EsRelation;
import org.elasticsearch.xpack.esql.plan.logical.Eval;
import org.elasticsearch.xpack.esql.plan.logical.Filter;
import org.elasticsearch.xpack.esql.plan.logical.Grok;
import org.elasticsearch.xpack.esql.plan.logical.InlineStats;
import org.elasticsearch.xpack.esql.plan.logical.Limit;
import org.elasticsearch.xpack.esql.plan.logical.Lookup;
import org.elasticsearch.xpack.esql.plan.logical.MvExpand;
import org.elasticsearch.xpack.esql.plan.logical.OrderBy;
import org.elasticsearch.xpack.esql.plan.logical.Project;
import org.elasticsearch.xpack.esql.plan.logical.Sample;
import org.elasticsearch.xpack.esql.plan.logical.TimeSeriesAggregate;
import org.elasticsearch.xpack.esql.plan.logical.TopN;
import org.elasticsearch.xpack.esql.plan.logical.inference.Completion;
import org.elasticsearch.xpack.esql.plan.logical.inference.Rerank;
import org.elasticsearch.xpack.esql.plan.logical.join.InlineJoin;
import org.elasticsearch.xpack.esql.plan.logical.join.Join;
import org.elasticsearch.xpack.esql.plan.logical.local.CopyingLocalSupplier;
import org.elasticsearch.xpack.esql.plan.logical.local.EmptyLocalSupplier;
import org.elasticsearch.xpack.esql.plan.logical.local.EsqlProject;
import org.elasticsearch.xpack.esql.plan.logical.local.ImmediateLocalSupplier;
import org.elasticsearch.xpack.esql.plan.logical.local.LocalRelation;
import org.elasticsearch.xpack.esql.plan.physical.AggregateExec;
import org.elasticsearch.xpack.esql.plan.physical.DissectExec;
import org.elasticsearch.xpack.esql.plan.physical.EnrichExec;
import org.elasticsearch.xpack.esql.plan.physical.EsQueryExec;
import org.elasticsearch.xpack.esql.plan.physical.EsSourceExec;
import org.elasticsearch.xpack.esql.plan.physical.EvalExec;
import org.elasticsearch.xpack.esql.plan.physical.ExchangeExec;
import org.elasticsearch.xpack.esql.plan.physical.ExchangeSinkExec;
import org.elasticsearch.xpack.esql.plan.physical.ExchangeSourceExec;
import org.elasticsearch.xpack.esql.plan.physical.FieldExtractExec;
import org.elasticsearch.xpack.esql.plan.physical.FilterExec;
import org.elasticsearch.xpack.esql.plan.physical.FragmentExec;
import org.elasticsearch.xpack.esql.plan.physical.GrokExec;
import org.elasticsearch.xpack.esql.plan.physical.HashJoinExec;
import org.elasticsearch.xpack.esql.plan.physical.LimitExec;
import org.elasticsearch.xpack.esql.plan.physical.LocalSourceExec;
import org.elasticsearch.xpack.esql.plan.physical.MvExpandExec;
import org.elasticsearch.xpack.esql.plan.physical.ProjectExec;
import org.elasticsearch.xpack.esql.plan.physical.SampleExec;
import org.elasticsearch.xpack.esql.plan.physical.ShowExec;
import org.elasticsearch.xpack.esql.plan.physical.SubqueryExec;
import org.elasticsearch.xpack.esql.plan.physical.TimeSeriesAggregateExec;
import org.elasticsearch.xpack.esql.plan.physical.TopNExec;
import org.elasticsearch.xpack.esql.plan.physical.inference.CompletionExec;
import org.elasticsearch.xpack.esql.plan.physical.inference.RerankExec;

import java.util.ArrayList;
import java.util.List;

public class PlanWritables {

    public static List<NamedWriteableRegistry.Entry> getNamedWriteables() {
        List<NamedWriteableRegistry.Entry> entries = new ArrayList<>();
        entries.addAll(logical());
        entries.addAll(physical());
        entries.addAll(others());
        return entries;
    }

    public static List<NamedWriteableRegistry.Entry> logical() {
        return List.of(
            Aggregate.ENTRY,
            Completion.ENTRY,
            Dissect.ENTRY,
            Enrich.ENTRY,
            EsRelation.ENTRY,
            EsqlProject.ENTRY,
            Eval.ENTRY,
            Filter.ENTRY,
            Grok.ENTRY,
            InlineJoin.ENTRY,
            InlineStats.ENTRY,
            Join.ENTRY,
            LocalRelation.ENTRY,
            Limit.ENTRY,
            Lookup.ENTRY,
            MvExpand.ENTRY,
            OrderBy.ENTRY,
            Project.ENTRY,
            Rerank.ENTRY,
            Sample.ENTRY,
            TimeSeriesAggregate.ENTRY,
            TopN.ENTRY
        );
    }

    public static List<NamedWriteableRegistry.Entry> physical() {
        return List.of(
            AggregateExec.ENTRY,
            CompletionExec.ENTRY,
            DissectExec.ENTRY,
            EnrichExec.ENTRY,
            EsQueryExec.ENTRY,
            EsSourceExec.ENTRY,
            EvalExec.ENTRY,
            ExchangeExec.ENTRY,
            ExchangeSinkExec.ENTRY,
            ExchangeSourceExec.ENTRY,
            FieldExtractExec.ENTRY,
            FilterExec.ENTRY,
            FragmentExec.ENTRY,
            GrokExec.ENTRY,
            HashJoinExec.ENTRY,
            LimitExec.ENTRY,
            LocalSourceExec.ENTRY,
            MvExpandExec.ENTRY,
            ProjectExec.ENTRY,
            RerankExec.ENTRY,
            SampleExec.ENTRY,
            ShowExec.ENTRY,
            SubqueryExec.ENTRY,
            TimeSeriesAggregateExec.ENTRY,
            TopNExec.ENTRY
        );
    }

    public static List<NamedWriteableRegistry.Entry> others() {
        return List.of(CopyingLocalSupplier.ENTRY, ImmediateLocalSupplier.ENTRY, EmptyLocalSupplier.ENTRY);
    }
}
