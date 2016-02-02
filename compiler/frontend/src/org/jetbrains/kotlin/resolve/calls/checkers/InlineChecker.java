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

package org.jetbrains.kotlin.resolve.calls.checkers;

import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.builtins.KotlinBuiltIns;
import org.jetbrains.kotlin.descriptors.*;
import org.jetbrains.kotlin.diagnostics.Errors;
import org.jetbrains.kotlin.lexer.KtToken;
import org.jetbrains.kotlin.lexer.KtTokens;
import org.jetbrains.kotlin.psi.*;
import org.jetbrains.kotlin.resolve.DescriptorUtils;
import org.jetbrains.kotlin.resolve.calls.callUtil.CallUtilKt;
import org.jetbrains.kotlin.resolve.calls.context.BasicCallResolutionContext;
import org.jetbrains.kotlin.resolve.calls.model.DefaultValueArgument;
import org.jetbrains.kotlin.resolve.calls.model.ResolvedCall;
import org.jetbrains.kotlin.resolve.calls.model.ResolvedValueArgument;
import org.jetbrains.kotlin.resolve.calls.model.VariableAsFunctionResolvedCall;
import org.jetbrains.kotlin.resolve.descriptorUtil.DescriptorUtilsKt;
import org.jetbrains.kotlin.resolve.inline.InlineUtil;
import org.jetbrains.kotlin.resolve.scopes.receivers.ExpressionReceiver;
import org.jetbrains.kotlin.resolve.scopes.receivers.ExtensionReceiver;
import org.jetbrains.kotlin.resolve.scopes.receivers.ReceiverValue;
import org.jetbrains.kotlin.util.OperatorNameConventions;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.jetbrains.kotlin.diagnostics.Errors.NON_LOCAL_RETURN_NOT_ALLOWED;
import static org.jetbrains.kotlin.diagnostics.Errors.USAGE_IS_NOT_INLINABLE;
import static org.jetbrains.kotlin.resolve.inline.InlineUtil.allowsNonLocalReturns;
import static org.jetbrains.kotlin.resolve.inline.InlineUtil.checkNonLocalReturnUsage;

class InlineChecker implements CallChecker {
    private final SimpleFunctionDescriptor descriptor;
    private final Set<CallableDescriptor> inlinableParameters = new LinkedHashSet<CallableDescriptor>();
    private final boolean isEffectivelyPublicApiFunction;

    public InlineChecker(@NotNull SimpleFunctionDescriptor descriptor) {
        assert InlineUtil.isInline(descriptor) : "This extension should be created only for inline functions: " + descriptor;
        this.descriptor = descriptor;
        this.isEffectivelyPublicApiFunction = DescriptorUtilsKt.isEffectivelyPublicApi(descriptor);

        for (ValueParameterDescriptor param : descriptor.getValueParameters()) {
            if (isInlinableParameter(param)) {
                inlinableParameters.add(param);
            }
        }
    }

    @Override
    public <F extends CallableDescriptor> void check(@NotNull ResolvedCall<F> resolvedCall, @NotNull BasicCallResolutionContext context) {
        KtExpression expression = context.call.getCalleeExpression();
        if (expression == null) {
            return;
        }

        //checking that only invoke or inlinable extension called on function parameter
        CallableDescriptor targetDescriptor = resolvedCall.getResultingDescriptor();
        checkCallWithReceiver(context, targetDescriptor, resolvedCall.getDispatchReceiver(), expression);
        checkCallWithReceiver(context, targetDescriptor, (ReceiverValue) resolvedCall.getExtensionReceiver(), expression);

        if (inlinableParameters.contains(targetDescriptor)) {
            if (!isInsideCall(expression)) {
                context.trace.report(USAGE_IS_NOT_INLINABLE.on(expression, expression, descriptor));
            }
        }

        for (Map.Entry<ValueParameterDescriptor, ResolvedValueArgument> entry : resolvedCall.getValueArguments().entrySet()) {
            ResolvedValueArgument value = entry.getValue();
            ValueParameterDescriptor valueDescriptor = entry.getKey();
            if (!(value instanceof DefaultValueArgument)) {
                for (ValueArgument argument : value.getArguments()) {
                    checkValueParameter(context, targetDescriptor, argument, valueDescriptor);
                }
            }
        }

        checkVisibilityAndAccess(targetDescriptor, expression, context);
        checkRecursion(context, targetDescriptor, expression);
    }

    private static boolean isInsideCall(KtExpression expression) {
        KtElement parent = KtPsiUtil.getParentCallIfPresent(expression);
        if (parent instanceof KtBinaryExpression) {
            KtToken token = KtPsiUtil.getOperationToken((KtOperationExpression) parent);
            if (token == KtTokens.EQ || token == KtTokens.ANDAND || token == KtTokens.OROR) {
                //assignment
                return false;
            }
        }

        if (parent != null) {
            //UGLY HACK
            //check there is no casts
            PsiElement current = expression;
            while (current != parent) {
                if (current instanceof KtBinaryExpressionWithTypeRHS) {
                    return false;
                }
                current = current.getParent();
            }
        }

        return parent != null;
    }



    private void checkValueParameter(
            @NotNull BasicCallResolutionContext context,
            @NotNull CallableDescriptor targetDescriptor,
            @NotNull ValueArgument targetArgument,
            @NotNull ValueParameterDescriptor targetParameterDescriptor
    ) {
        KtExpression argumentExpression = targetArgument.getArgumentExpression();
        if (argumentExpression == null) {
            return;
        }
        CallableDescriptor argumentCallee = getCalleeDescriptor(context, argumentExpression, false);

        if (argumentCallee != null && inlinableParameters.contains(argumentCallee)) {
            if (InlineUtil.isInline(targetDescriptor) && isInlinableParameter(targetParameterDescriptor)) {
                if (allowsNonLocalReturns(argumentCallee) && !allowsNonLocalReturns(targetParameterDescriptor)) {
                    context.trace.report(NON_LOCAL_RETURN_NOT_ALLOWED.on(argumentExpression, argumentExpression));
                }
                else {
                    checkNonLocalReturn(context, argumentCallee, argumentExpression);
                }
            }
            else {
                context.trace.report(USAGE_IS_NOT_INLINABLE.on(argumentExpression, argumentExpression, descriptor));
            }
        }
    }

    private void checkCallWithReceiver(
            @NotNull BasicCallResolutionContext context,
            @NotNull CallableDescriptor targetDescriptor,
            @Nullable ReceiverValue receiver,
            @Nullable KtExpression expression
    ) {
        if (receiver == null) return;

        CallableDescriptor varDescriptor = null;
        KtExpression receiverExpression = null;
        if (receiver instanceof ExpressionReceiver) {
            receiverExpression = ((ExpressionReceiver) receiver).getExpression();
            varDescriptor = getCalleeDescriptor(context, receiverExpression, true);
        }
        else if (receiver instanceof ExtensionReceiver) {
            ExtensionReceiver extensionReceiver = (ExtensionReceiver) receiver;
            CallableDescriptor extension = extensionReceiver.getDeclarationDescriptor();

            varDescriptor = extension.getExtensionReceiverParameter();
            assert varDescriptor != null : "Extension should have receiverParameterDescriptor: " + extension;

            receiverExpression = expression;
        }

        if (inlinableParameters.contains(varDescriptor)) {
            //check that it's invoke or inlinable extension
            checkLambdaInvokeOrExtensionCall(context, varDescriptor, targetDescriptor, receiverExpression);
        }
    }

    @Nullable
    private static CallableDescriptor getCalleeDescriptor(
            @NotNull BasicCallResolutionContext context,
            @NotNull KtExpression expression,
            boolean unwrapVariableAsFunction
    ) {
        if (!(expression instanceof KtSimpleNameExpression || expression instanceof KtThisExpression)) return null;

        ResolvedCall<?> thisCall = CallUtilKt.getResolvedCall(expression, context.trace.getBindingContext());
        if (unwrapVariableAsFunction && thisCall instanceof VariableAsFunctionResolvedCall) {
            return ((VariableAsFunctionResolvedCall) thisCall).getVariableCall().getResultingDescriptor();
        }
        return thisCall != null ? thisCall.getResultingDescriptor() : null;
    }

    private void checkLambdaInvokeOrExtensionCall(
            @NotNull BasicCallResolutionContext context,
            @NotNull CallableDescriptor lambdaDescriptor,
            @NotNull CallableDescriptor callDescriptor,
            @NotNull KtExpression receiverExpression
    ) {
        boolean inlinableCall = isInvokeOrInlineExtension(callDescriptor);
        if (!inlinableCall) {
            context.trace.report(USAGE_IS_NOT_INLINABLE.on(receiverExpression, receiverExpression, descriptor));
        }
        else {
            checkNonLocalReturn(context, lambdaDescriptor, receiverExpression);
        }
    }

    public void checkRecursion(
            @NotNull BasicCallResolutionContext context,
            @NotNull CallableDescriptor targetDescriptor,
            @NotNull KtElement expression
    ) {
        if (targetDescriptor.getOriginal() == descriptor) {
            context.trace.report(Errors.RECURSION_IN_INLINE.on(expression, expression, descriptor));
        }
    }

    private static boolean isInlinableParameter(@NotNull ParameterDescriptor descriptor) {
        return InlineUtil.isInlineLambdaParameter(descriptor) && !descriptor.getType().isMarkedNullable();
    }

    private static boolean isInvokeOrInlineExtension(@NotNull CallableDescriptor descriptor) {
        if (!(descriptor instanceof SimpleFunctionDescriptor)) {
            return false;
        }

        DeclarationDescriptor containingDeclaration = descriptor.getContainingDeclaration();
        boolean isInvoke = descriptor.getName().equals(OperatorNameConventions.INVOKE) &&
                           containingDeclaration instanceof ClassDescriptor &&
                           KotlinBuiltIns.isExactFunctionOrExtensionFunctionType(((ClassDescriptor) containingDeclaration).getDefaultType());

        return isInvoke || InlineUtil.isInline(descriptor);
    }

    private void checkVisibilityAndAccess(@NotNull CallableDescriptor declarationDescriptor, @NotNull KtElement expression, @NotNull BasicCallResolutionContext context){
        boolean declarationDescriptorIsPublicApi = DescriptorUtilsKt.isEffectivelyPublicApi(declarationDescriptor) || isDefinedInInlineFunction(declarationDescriptor);
        if (isEffectivelyPublicApiFunction && !declarationDescriptorIsPublicApi && declarationDescriptor.getVisibility() != Visibilities.LOCAL) {
            context.trace.report(Errors.INVISIBLE_MEMBER_FROM_INLINE.on(expression, declarationDescriptor, descriptor));
        }
        else {
            checkPrivateClassMemberAccess(declarationDescriptor, expression, context);
        }
    }

    private void checkPrivateClassMemberAccess(
            @NotNull DeclarationDescriptor declarationDescriptor,
            @NotNull KtElement expression,
            @NotNull BasicCallResolutionContext context
    ) {
        if (!Visibilities.isPrivate(descriptor.getVisibility())) {
            if (DescriptorUtilsKt.isInsidePrivateClass(declarationDescriptor)) {
                context.trace.report(Errors.PRIVATE_CLASS_MEMBER_FROM_INLINE.on(expression, declarationDescriptor, descriptor));
            }
        }
    }

    private boolean isDefinedInInlineFunction(@NotNull DeclarationDescriptorWithVisibility startDescriptor) {
        DeclarationDescriptorWithVisibility parent = startDescriptor;

        while (parent != null) {
            if (parent.getContainingDeclaration() == descriptor) return true;

            parent = DescriptorUtils.getParentOfType(parent, DeclarationDescriptorWithVisibility.class);
        }

        return false;
    }

    private void checkNonLocalReturn(
            @NotNull BasicCallResolutionContext context,
            @NotNull CallableDescriptor inlinableParameterDescriptor,
            @NotNull KtExpression parameterUsage
    ) {
        if (!allowsNonLocalReturns(inlinableParameterDescriptor)) return;

        if (!checkNonLocalReturnUsage(descriptor, parameterUsage, context.trace)) {
            context.trace.report(NON_LOCAL_RETURN_NOT_ALLOWED.on(parameterUsage, parameterUsage));
        }
    }
}
