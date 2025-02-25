/*
 * Copyright © 2016-2019 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package io.cdap.wrangler.executor;

import io.cdap.cdap.api.data.format.StructuredRecord;
import io.cdap.cdap.api.data.schema.Schema;
import io.cdap.wrangler.api.Directive;
import io.cdap.wrangler.api.DirectiveExecutionException;
import io.cdap.wrangler.api.DirectiveLoadException;
import io.cdap.wrangler.api.DirectiveNotFoundException;
import io.cdap.wrangler.api.DirectiveParseException;
import io.cdap.wrangler.api.ErrorRecord;
import io.cdap.wrangler.api.ErrorRowException;
import io.cdap.wrangler.api.Executor;
import io.cdap.wrangler.api.ExecutorContext;
import io.cdap.wrangler.api.RecipeException;
import io.cdap.wrangler.api.RecipeParser;
import io.cdap.wrangler.api.RecipePipeline;
import io.cdap.wrangler.api.ReportErrorAndProceed;
import io.cdap.wrangler.api.Row;
import io.cdap.wrangler.api.TransientVariableScope;
import io.cdap.wrangler.utils.RecordConvertor;
import io.cdap.wrangler.utils.RecordConvertorException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;

/**
 * The class <code>RecipePipelineExecutor</code> compiles the recipe and executes the directives.
 */
public final class RecipePipelineExecutor implements RecipePipeline<Row, StructuredRecord, ErrorRecord> {

  private static final Logger LOG = LoggerFactory.getLogger(RecipePipelineExecutor.class);

  private final ErrorRecordCollector collector = new ErrorRecordCollector();
  private final RecordConvertor convertor = new RecordConvertor();
  private final RecipeParser recipeParser;
  private final ExecutorContext context;
  private List<Directive> directives;

  public RecipePipelineExecutor(RecipeParser recipeParser, @Nullable ExecutorContext context) {
    this.context = context;
    this.recipeParser = recipeParser;
  }

  /**
   * Invokes each directives destroy method to perform any cleanup required by each individual directive.
   */
  @Override
  public void close() {
    if (directives == null) {
      return;
    }
    for (Directive directive : directives) {
      try {
        directive.destroy();
      } catch (Throwable t) {
        LOG.warn(t.getMessage(), t);
      }
    }
  }

  /**
   * Executes the pipeline on the input.
   *
   * @param rows List of Input record of type I.
   * @param schema Schema to which the output should be mapped.
   * @return Parsed output list of record of type O
   */
  @Override
  public List<StructuredRecord> execute(List<Row> rows, Schema schema) throws RecipeException {
    try {
      return convertor.toStructureRecord(execute(rows), schema);
    } catch (RecordConvertorException e) {
      throw new RecipeException("Problem converting into output record. Reason : " + e.getMessage(), e);
    }
  }

  /**
   * Executes the pipeline on the input.
   *
   * @param rows List of input record of type I.
   * @return Parsed output list of record of type I
   */
  @Override
  public List<Row> execute(List<Row> rows) throws RecipeException {
    List<Directive> directives = getDirectives();
    List<String> messages = new ArrayList<>();
    List<Row> results = new ArrayList<>();
    try {
      int i = 0;
      collector.reset();
      while (i < rows.size()) {
        messages.clear();
        // Resets the scope of local variable.
        if (context != null) {
          context.getTransientStore().reset(TransientVariableScope.LOCAL);
        }

        List<Row> cumulativeRows = rows.subList(i, i + 1);
        try {
          for (Executor<List<Row>, List<Row>> directive : directives) {
            try {
              cumulativeRows = directive.execute(cumulativeRows, context);
              if (cumulativeRows.size() < 1) {
                break;
              }
            } catch (ReportErrorAndProceed e) {
              messages.add(String.format("%s (ecode: %d)", e.getMessage(), e.getCode()));
              collector
                .add(new ErrorRecord(rows.subList(i, i + 1).get(0), String.join(",", messages), e.getCode(), true));
              cumulativeRows = new ArrayList<>();
              break;
            }
          }
          results.addAll(cumulativeRows);
        } catch (ErrorRowException e) {
          LOG.debug("Error while applying directives", e);
          messages.add(String.format("%s", e.getMessage()));
          collector
            .add(new ErrorRecord(rows.subList(i, i + 1).get(0), String.join(",", messages), e.getCode(),
              e.isShownInWrangler()));
        }
        ++i;
      }
    } catch (DirectiveExecutionException e) {
      throw new RecipeException(e.getMessage(), e);
    }
    return results;
  }

  /**
   * Returns records that are errored out.
   *
   * @return records that have errored out.
   */
  @Override
  public List<ErrorRecord> errors() {
    return collector.get();
  }

  private List<Directive> getDirectives() throws RecipeException {
    if (directives == null) {
      try {
        this.directives = recipeParser.parse();
      } catch (DirectiveParseException | DirectiveNotFoundException | DirectiveLoadException e) {
        throw new RecipeException(e.getMessage(), e);
      }
    }
    return directives;
  }
}
