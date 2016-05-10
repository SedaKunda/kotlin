/*
 * Copyright 2010-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.js.translate.utils.mutator;

import com.google.dart.compiler.backend.js.ast.JsBinaryOperation;
import com.google.dart.compiler.backend.js.ast.JsExpression;
import com.google.dart.compiler.backend.js.ast.JsNode;
import com.google.dart.compiler.backend.js.ast.metadata.MetadataProperties;
import org.jetbrains.annotations.NotNull;

import static org.jetbrains.kotlin.js.translate.utils.JsAstUtils.assignment;

public final class AssignToExpressionMutator implements Mutator {

    @NotNull
    private final JsExpression toAssign;

    public AssignToExpressionMutator(@NotNull JsExpression toAssign) {
        this.toAssign = toAssign;
    }

    @NotNull
    @Override
    public JsNode mutate(@NotNull JsNode node) {
        if (!(node instanceof JsExpression)) {
            return node;
        }
        JsBinaryOperation result = assignment(toAssign, (JsExpression) node);
        MetadataProperties.setSynthetic(result, true);
        return result;
    }
}
