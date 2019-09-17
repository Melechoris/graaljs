/*
 * Copyright (c) 2018, 2019, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or
 * data (collectively the "Software"), free of charge and under any and all
 * copyright rights in the Software, and any and all patent rights owned or
 * freely licensable by each licensor hereunder covering either (i) the
 * unmodified Software as contributed to or provided by such licensor, or (ii)
 * the Larger Works (as defined below), to deal in both
 *
 * (a) the Software, and
 *
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 *
 * The above copyright notice and either this complete permission notice or at a
 * minimum a reference to the UPL must be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.oracle.truffle.js.nodes.access;

import static com.oracle.truffle.js.runtime.builtins.JSAbstractArray.arrayGetAllocationSite;
import static com.oracle.truffle.js.runtime.builtins.JSAbstractArray.arrayGetArrayType;
import static com.oracle.truffle.js.runtime.builtins.JSAbstractArray.arraySetArrayType;

import java.util.Set;
import java.util.concurrent.locks.Lock;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.TruffleLanguage.ContextReference;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.instrumentation.InstrumentableNode;
import com.oracle.truffle.api.instrumentation.Tag;
import com.oracle.truffle.api.interop.ArityException;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.InvalidArrayIndexException;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnknownIdentifierException;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.interop.UnsupportedTypeException;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.UnexpectedResultException;
import com.oracle.truffle.api.object.DynamicObject;
import com.oracle.truffle.api.profiles.BranchProfile;
import com.oracle.truffle.api.profiles.ConditionProfile;
import com.oracle.truffle.js.nodes.JavaScriptBaseNode;
import com.oracle.truffle.js.nodes.JavaScriptNode;
import com.oracle.truffle.js.nodes.cast.JSToBigIntNode;
import com.oracle.truffle.js.nodes.cast.JSToDoubleNode;
import com.oracle.truffle.js.nodes.cast.JSToInt32Node;
import com.oracle.truffle.js.nodes.cast.JSToNumberNode;
import com.oracle.truffle.js.nodes.cast.JSToPropertyKeyNode;
import com.oracle.truffle.js.nodes.cast.JSToPropertyKeyNode.JSToPropertyKeyWrapperNode;
import com.oracle.truffle.js.nodes.cast.ToArrayIndexNode;
import com.oracle.truffle.js.nodes.instrumentation.JSTaggedExecutionNode;
import com.oracle.truffle.js.nodes.instrumentation.JSTags.WriteElementTag;
import com.oracle.truffle.js.nodes.interop.ExportValueNode;
import com.oracle.truffle.js.runtime.BigInt;
import com.oracle.truffle.js.runtime.Boundaries;
import com.oracle.truffle.js.runtime.Errors;
import com.oracle.truffle.js.runtime.JSContext;
import com.oracle.truffle.js.runtime.JSException;
import com.oracle.truffle.js.runtime.JSRealm;
import com.oracle.truffle.js.runtime.JSRuntime;
import com.oracle.truffle.js.runtime.JSTruffleOptions;
import com.oracle.truffle.js.runtime.Symbol;
import com.oracle.truffle.js.runtime.array.ArrayAllocationSite;
import com.oracle.truffle.js.runtime.array.ScriptArray;
import com.oracle.truffle.js.runtime.array.SparseArray;
import com.oracle.truffle.js.runtime.array.TypedArray;
import com.oracle.truffle.js.runtime.array.TypedArray.TypedBigIntArray;
import com.oracle.truffle.js.runtime.array.TypedArray.TypedFloatArray;
import com.oracle.truffle.js.runtime.array.TypedArray.TypedIntArray;
import com.oracle.truffle.js.runtime.array.TypedArray.Uint8ClampedArray;
import com.oracle.truffle.js.runtime.array.dyn.AbstractConstantArray;
import com.oracle.truffle.js.runtime.array.dyn.AbstractContiguousDoubleArray;
import com.oracle.truffle.js.runtime.array.dyn.AbstractContiguousIntArray;
import com.oracle.truffle.js.runtime.array.dyn.AbstractContiguousJSObjectArray;
import com.oracle.truffle.js.runtime.array.dyn.AbstractContiguousObjectArray;
import com.oracle.truffle.js.runtime.array.dyn.AbstractDoubleArray;
import com.oracle.truffle.js.runtime.array.dyn.AbstractIntArray;
import com.oracle.truffle.js.runtime.array.dyn.AbstractJSObjectArray;
import com.oracle.truffle.js.runtime.array.dyn.AbstractObjectArray;
import com.oracle.truffle.js.runtime.array.dyn.AbstractWritableArray;
import com.oracle.truffle.js.runtime.array.dyn.ConstantEmptyArray;
import com.oracle.truffle.js.runtime.array.dyn.ContiguousIntArray;
import com.oracle.truffle.js.runtime.array.dyn.HolesDoubleArray;
import com.oracle.truffle.js.runtime.array.dyn.HolesIntArray;
import com.oracle.truffle.js.runtime.array.dyn.HolesJSObjectArray;
import com.oracle.truffle.js.runtime.array.dyn.HolesObjectArray;
import com.oracle.truffle.js.runtime.array.dyn.LazyRegexResultArray;
import com.oracle.truffle.js.runtime.builtins.JSArray;
import com.oracle.truffle.js.runtime.builtins.JSArrayBufferView;
import com.oracle.truffle.js.runtime.builtins.JSBigInt;
import com.oracle.truffle.js.runtime.builtins.JSBoolean;
import com.oracle.truffle.js.runtime.builtins.JSNumber;
import com.oracle.truffle.js.runtime.builtins.JSSlowArgumentsObject;
import com.oracle.truffle.js.runtime.builtins.JSSlowArray;
import com.oracle.truffle.js.runtime.builtins.JSString;
import com.oracle.truffle.js.runtime.builtins.JSSymbol;
import com.oracle.truffle.js.runtime.objects.JSObject;
import com.oracle.truffle.js.runtime.util.JSClassProfile;
import com.oracle.truffle.js.runtime.util.TRegexUtil;

public class WriteElementNode extends JSTargetableNode {
    @Child protected JavaScriptNode targetNode;
    @Child protected JavaScriptNode indexNode;
    @Child private ToArrayIndexNode toArrayIndexNode;
    @Child protected JavaScriptNode valueNode;
    @Child private WriteElementTypeCacheNode typeCacheNode;
    @Child private RequireObjectCoercibleNode requireObjectCoercibleNode;

    final JSContext context;
    final boolean isStrict;
    final boolean writeOwn;
    @CompilationFinal private byte indexState;
    private static final byte INDEX_INT = 1;
    private static final byte INDEX_OBJECT = 2;

    public static WriteElementNode create(JSContext context, boolean isStrict) {
        return create(null, null, null, context, isStrict, false);
    }

    public static WriteElementNode create(JSContext context, boolean isStrict, boolean writeOwn) {
        return create(null, null, null, context, isStrict, writeOwn);
    }

    public static WriteElementNode create(JavaScriptNode targetNode, JavaScriptNode indexNode, JavaScriptNode valueNode, JSContext context, boolean isStrict) {
        return create(targetNode, indexNode, valueNode, context, isStrict, false);
    }

    private static WriteElementNode create(JavaScriptNode targetNode, JavaScriptNode indexNode, JavaScriptNode valueNode, JSContext context, boolean isStrict, boolean writeOwn) {
        return new WriteElementNode(targetNode, indexNode, valueNode, context, isStrict, writeOwn);
    }

    protected WriteElementNode(JavaScriptNode targetNode, JavaScriptNode indexNode, JavaScriptNode valueNode, JSContext context, boolean isStrict, boolean writeOwn) {
        // ToPropertyKey conversion should not be performed by indexNode
        // (we need to RequireObjectCoercible(target) before this conversion)
        assert !(indexNode instanceof JSToPropertyKeyWrapperNode);

        this.targetNode = targetNode;
        this.indexNode = indexNode;
        this.valueNode = valueNode;
        this.context = context;
        this.isStrict = isStrict;
        this.writeOwn = writeOwn;
        this.requireObjectCoercibleNode = RequireObjectCoercibleNode.create();
    }

    protected final ToArrayIndexNode toArrayIndexNode() {
        if (toArrayIndexNode == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            toArrayIndexNode = insert(ToArrayIndexNode.create());
        }
        return toArrayIndexNode;
    }

    protected final void requireObjectCoercible(Object target, int index) {
        try {
            requireObjectCoercibleNode.executeVoid(target);
        } catch (JSException e) {
            throw Errors.createTypeErrorCannotSetProperty(JSRuntime.safeToString(index), target, this);
        }
    }

    protected final void requireObjectCoercible(Object target, Object index) {
        try {
            requireObjectCoercibleNode.executeVoid(target);
        } catch (JSException e) {
            throw Errors.createTypeErrorCannotSetProperty(JSRuntime.safeToString(index), target, this);
        }
    }

    @Override
    public boolean hasTag(Class<? extends Tag> tag) {
        if (tag == WriteElementTag.class) {
            return true;
        } else {
            return super.hasTag(tag);
        }
    }

    @Override
    public InstrumentableNode materializeInstrumentableNodes(Set<Class<? extends Tag>> materializedTags) {
        if (materializationNeeded() && materializedTags.contains(WriteElementTag.class)) {
            JavaScriptNode clonedTarget = targetNode == null || targetNode.hasSourceSection() ? targetNode : JSTaggedExecutionNode.createForInput(targetNode, this);
            JavaScriptNode clonedIndex = indexNode == null || indexNode.hasSourceSection() ? indexNode : JSTaggedExecutionNode.createForInput(indexNode, this);
            JavaScriptNode clonedValue = valueNode == null || valueNode.hasSourceSection() ? valueNode : JSTaggedExecutionNode.createForInput(valueNode, this);
            WriteElementNode cloned = createMaterialized(clonedTarget, clonedIndex, clonedValue);
            transferSourceSectionAndTags(this, cloned);
            return cloned;
        }
        return this;
    }

    private boolean materializationNeeded() {
        // Materialization is needed when source sections are missing.
        return (targetNode != null && !targetNode.hasSourceSection()) || (indexNode != null && !indexNode.hasSourceSection()) || (valueNode != null && !valueNode.hasSourceSection());
    }

    protected WriteElementNode createMaterialized(JavaScriptNode newTarget, JavaScriptNode newIndex, JavaScriptNode newValue) {
        return WriteElementNode.create(newTarget, newIndex, newValue, getContext(), isStrict(), writeOwn());
    }

    @Override
    public Object evaluateTarget(VirtualFrame frame) {
        return targetNode.execute(frame);
    }

    @Override
    public Object execute(VirtualFrame frame) {
        Object target = evaluateTarget(frame);
        return executeWithTarget(frame, target, evaluateReceiver(targetNode, frame, target));
    }

    @Override
    public int executeInt(VirtualFrame frame) throws UnexpectedResultException {
        Object target = evaluateTarget(frame);
        return executeWithTargetInt(frame, target, evaluateReceiver(targetNode, frame, target));
    }

    @Override
    public double executeDouble(VirtualFrame frame) throws UnexpectedResultException {
        Object target = evaluateTarget(frame);
        return executeWithTargetDouble(frame, target, evaluateReceiver(targetNode, frame, target));
    }

    @Override
    public Object executeWithTarget(VirtualFrame frame, Object target) {
        return executeWithTarget(frame, target, target);
    }

    public Object executeWithTarget(VirtualFrame frame, Object target, Object receiver) {
        byte is = indexState;
        if (is == 0) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            Object index = indexNode.execute(frame);
            requireObjectCoercible(target, index);
            if (index instanceof Integer) {
                indexState = INDEX_INT;
                return executeWithTargetAndIndex(frame, target, (int) index, receiver);
            } else {
                indexState = INDEX_OBJECT;
                return executeWithTargetAndIndex(frame, target, toArrayIndexNode().execute(index), receiver);
            }
        } else if (is == INDEX_INT) {
            int index;
            try {
                index = indexNode.executeInt(frame);
            } catch (UnexpectedResultException e) {
                indexState = INDEX_OBJECT;
                requireObjectCoercible(target, e.getResult());
                return executeWithTargetAndIndex(frame, target, toArrayIndexNode().execute(e.getResult()), receiver);
            }
            requireObjectCoercible(target, index);
            return executeWithTargetAndIndex(frame, target, index, receiver);
        } else {
            assert is == INDEX_OBJECT;
            Object index = indexNode.execute(frame);
            requireObjectCoercible(target, index);
            return executeWithTargetAndIndex(frame, target, toArrayIndexNode().execute(index), receiver);
        }
    }

    public int executeWithTargetInt(VirtualFrame frame, Object target, Object receiver) throws UnexpectedResultException {
        byte is = indexState;
        if (is == 0) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            Object index = indexNode.execute(frame);
            requireObjectCoercible(target, index);
            if (index instanceof Integer) {
                indexState = INDEX_INT;
                return executeWithTargetAndIndexInt(frame, target, (int) index, receiver);
            } else {
                indexState = INDEX_OBJECT;
                return executeWithTargetAndIndexInt(frame, target, toArrayIndexNode().execute(index), receiver);
            }
        } else if (is == INDEX_INT) {
            int index;
            try {
                index = indexNode.executeInt(frame);
            } catch (UnexpectedResultException e) {
                indexState = INDEX_OBJECT;
                requireObjectCoercible(target, e.getResult());
                return executeWithTargetAndIndexInt(frame, target, toArrayIndexNode().execute(e.getResult()), receiver);
            }
            requireObjectCoercible(target, index);
            return executeWithTargetAndIndexInt(frame, target, index, receiver);
        } else {
            assert is == INDEX_OBJECT;
            Object index = indexNode.execute(frame);
            requireObjectCoercible(target, index);
            return executeWithTargetAndIndexInt(frame, target, toArrayIndexNode().execute(index), receiver);
        }
    }

    public double executeWithTargetDouble(VirtualFrame frame, Object target, Object receiver) throws UnexpectedResultException {
        byte is = indexState;
        if (is == 0) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            Object index = indexNode.execute(frame);
            requireObjectCoercible(target, index);
            if (index instanceof Integer) {
                indexState = INDEX_INT;
                return executeWithTargetAndIndexDouble(frame, target, (int) index, receiver);
            } else {
                indexState = INDEX_OBJECT;
                return executeWithTargetAndIndexDouble(frame, target, toArrayIndexNode().execute(index), receiver);
            }
        } else if (is == INDEX_INT) {
            int index;
            try {
                index = indexNode.executeInt(frame);
            } catch (UnexpectedResultException e) {
                indexState = INDEX_OBJECT;
                requireObjectCoercible(target, e.getResult());
                return executeWithTargetAndIndexDouble(frame, target, toArrayIndexNode().execute(e.getResult()), receiver);
            }
            requireObjectCoercible(target, index);
            return executeWithTargetAndIndexDouble(frame, target, index, receiver);
        } else {
            assert is == INDEX_OBJECT;
            Object index = indexNode.execute(frame);
            requireObjectCoercible(target, index);
            return executeWithTargetAndIndexDouble(frame, target, toArrayIndexNode().execute(index), receiver);
        }
    }

    protected Object executeWithTargetAndIndex(VirtualFrame frame, Object target, Object index, Object receiver) {
        Object value = valueNode.execute(frame);
        executeWithTargetAndIndexAndValue(target, index, value, receiver);
        return value;
    }

    protected Object executeWithTargetAndIndex(VirtualFrame frame, Object target, int index, Object receiver) {
        Object value = valueNode.execute(frame);
        executeWithTargetAndIndexAndValue(target, index, value, receiver);
        return value;
    }

    protected int executeWithTargetAndIndexInt(VirtualFrame frame, Object target, Object index, Object receiver) throws UnexpectedResultException {
        try {
            int value = valueNode.executeInt(frame);
            executeWithTargetAndIndexAndValue(target, index, value, receiver);
            return value;
        } catch (UnexpectedResultException e) {
            executeWithTargetAndIndexAndValue(target, index, e.getResult(), receiver);
            throw e;
        }
    }

    protected int executeWithTargetAndIndexInt(VirtualFrame frame, Object target, int index, Object receiver) throws UnexpectedResultException {
        try {
            int value = valueNode.executeInt(frame);
            executeWithTargetAndIndexAndValue(target, index, (Object) value, receiver);
            return value;
        } catch (UnexpectedResultException e) {
            executeWithTargetAndIndexAndValue(target, index, e.getResult(), receiver);
            throw e;
        }
    }

    protected double executeWithTargetAndIndexDouble(VirtualFrame frame, Object target, Object index, Object receiver) throws UnexpectedResultException {
        try {
            double value = valueNode.executeDouble(frame);
            executeWithTargetAndIndexAndValue(target, index, value, receiver);
            return value;
        } catch (UnexpectedResultException e) {
            executeWithTargetAndIndexAndValue(target, index, e.getResult(), receiver);
            throw e;
        }
    }

    protected double executeWithTargetAndIndexDouble(VirtualFrame frame, Object target, int index, Object receiver) throws UnexpectedResultException {
        try {
            double value = valueNode.executeDouble(frame);
            executeWithTargetAndIndexAndValue(target, index, (Object) value, receiver);
            return value;
        } catch (UnexpectedResultException e) {
            executeWithTargetAndIndexAndValue(target, index, e.getResult(), receiver);
            throw e;
        }
    }

    public final void executeWithTargetAndIndexAndValue(Object target, Object index, Object value) {
        getTypeCacheNode().executeWithTargetAndIndexAndValue(target, index, value, target);
    }

    public final void executeWithTargetAndIndexAndValue(Object target, int index, Object value) {
        getTypeCacheNode().executeWithTargetAndIndexAndValue(target, index, value, target);
    }

    public final void executeWithTargetAndIndexAndValue(Object target, Object index, Object value, Object receiver) {
        getTypeCacheNode().executeWithTargetAndIndexAndValue(target, index, value, receiver);
    }

    public final void executeWithTargetAndIndexAndValue(Object target, int index, Object value, Object receiver) {
        getTypeCacheNode().executeWithTargetAndIndexAndValue(target, index, value, receiver);
    }

    protected abstract static class WriteElementCacheNode extends JavaScriptBaseNode {
        protected final JSContext context;
        protected final boolean isStrict;
        protected final boolean writeOwn;

        protected WriteElementCacheNode(JSContext context, boolean isStrict, boolean writeOwn) {
            this.context = context;
            this.isStrict = isStrict;
            this.writeOwn = writeOwn;
        }
    }

    abstract static class WriteElementTypeCacheNode extends WriteElementCacheNode {
        protected WriteElementTypeCacheNode(JSContext context, boolean isStrict, boolean writeOwn) {
            super(context, isStrict, writeOwn);
        }

        public abstract void executeWithTargetAndIndexAndValue(Object target, Object index, Object value, Object receiver);

        public void executeWithTargetAndIndexAndValue(Object target, int index, Object value, Object receiver) {
            executeWithTargetAndIndexAndValue(target, (Object) index, value, receiver);
        }
    }

    private static class UninitWriteElementTypeCacheNode extends WriteElementTypeCacheNode {
        UninitWriteElementTypeCacheNode(JSContext context, boolean isStrict, boolean writeOwn) {
            super(context, isStrict, writeOwn);
        }

        @Override
        public void executeWithTargetAndIndexAndValue(Object target, Object index, Object value, Object receiver) {
            CompilerDirectives.transferToInterpreterAndInvalidate();

            CachedWriteElementTypeCacheNode specialized = makeTypeCacheNode(target);
            this.replace(specialized);
            checkForPolymorphicSpecialize();
            specialized.executeWithTargetAndIndexAndValue(target, index, value, receiver);
        }

        private void checkForPolymorphicSpecialize() {
            Node parent = getParent();
            if (parent != null && parent instanceof CachedWriteElementTypeCacheNode) {
                reportPolymorphicSpecialize();
            }
        }

        @SuppressWarnings("unchecked")
        private CachedWriteElementTypeCacheNode makeTypeCacheNode(Object target) {
            if (JSObject.isJSObject(target)) {
                return new JSObjectWriteElementTypeCacheNode(context, isStrict, writeOwn);
            } else if (JSRuntime.isString(target)) {
                return new StringWriteElementTypeCacheNode(context, isStrict, target.getClass(), writeOwn);
            } else if (target instanceof Boolean) {
                return new BooleanWriteElementTypeCacheNode(context, isStrict, writeOwn);
            } else if (target instanceof Number) {
                return new NumberWriteElementTypeCacheNode(context, isStrict, target.getClass(), writeOwn);
            } else if (target instanceof Symbol) {
                return new SymbolWriteElementTypeCacheNode(context, isStrict, writeOwn);
            } else if (target instanceof BigInt) {
                return new BigIntWriteElementTypeCacheNode(context, isStrict, writeOwn);
            } else if (target instanceof TruffleObject) {
                assert JSRuntime.isForeignObject(target);
                return new TruffleObjectWriteElementTypeCacheNode(context, isStrict, (Class<? extends TruffleObject>) target.getClass(), writeOwn);
            } else {
                assert JSRuntime.isJavaPrimitive(target);
                return new JavaObjectWriteElementTypeCacheNode(context, isStrict, target.getClass(), writeOwn);
            }
        }
    }

    private abstract static class CachedWriteElementTypeCacheNode extends WriteElementTypeCacheNode {
        @Child private WriteElementTypeCacheNode typeCacheNext;

        CachedWriteElementTypeCacheNode(JSContext context, boolean isStrict, boolean writeOwn) {
            super(context, isStrict, writeOwn);
        }

        @Override
        public void executeWithTargetAndIndexAndValue(Object target, Object index, Object value, Object receiver) {
            if (guard(target)) {
                executeWithTargetAndIndexUnguarded(target, index, value, receiver);
            } else {
                getNext().executeWithTargetAndIndexAndValue(target, index, value, receiver);
            }
        }

        @Override
        public void executeWithTargetAndIndexAndValue(Object target, int index, Object value, Object receiver) {
            if (guard(target)) {
                executeWithTargetAndIndexUnguarded(target, index, value, receiver);
            } else {
                getNext().executeWithTargetAndIndexAndValue(target, index, value, receiver);
            }
        }

        protected abstract void executeWithTargetAndIndexUnguarded(Object target, Object index, Object value, Object receiver);

        protected abstract void executeWithTargetAndIndexUnguarded(Object target, int index, Object value, Object receiver);

        public abstract boolean guard(Object target);

        private WriteElementTypeCacheNode getNext() {
            if (typeCacheNext == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                typeCacheNext = insert(new UninitWriteElementTypeCacheNode(context, isStrict, writeOwn));
            }
            return typeCacheNext;
        }
    }

    private static class JSObjectWriteElementTypeCacheNode extends CachedWriteElementTypeCacheNode {
        @Child private IsArrayNode isArrayNode;
        @Child private ToArrayIndexNode toArrayIndexNode;
        @Child private ArrayWriteElementCacheNode arrayWriteElementNode;
        @Child private IsJSObjectNode isObjectNode;
        private final ConditionProfile intOrStringIndexProfile = ConditionProfile.createBinaryProfile();
        private final ConditionProfile arrayProfile = ConditionProfile.createBinaryProfile();
        private final JSClassProfile jsclassProfile = JSClassProfile.create();
        @Child private CachedSetPropertyNode setPropertyCachedNode;

        JSObjectWriteElementTypeCacheNode(JSContext context, boolean isStrict, boolean writeOwn) {
            super(context, isStrict, writeOwn);
            this.isArrayNode = IsArrayNode.createIsFastOrTypedArray();
            this.isObjectNode = IsJSObjectNode.createIncludeNullUndefined();
        }

        @Override
        protected void executeWithTargetAndIndexUnguarded(Object target, Object index, Object value, Object receiver) {
            DynamicObject targetObject = JSObject.castJSObject(target);
            boolean arrayCondition = isArrayNode.execute(targetObject);
            if (arrayProfile.profile(arrayCondition)) {
                ScriptArray array = JSObject.getArray(targetObject, arrayCondition);
                Object objIndex = toArrayIndex(index);

                if (intOrStringIndexProfile.profile(objIndex instanceof Long)) {
                    long longIndex = (Long) objIndex;
                    if (!getArrayWriteElementNode().executeWithTargetAndArrayAndIndexAndValue(targetObject, array, longIndex, value, arrayCondition)) {
                        setPropertyGenericEvaluatedIndex(targetObject, longIndex, value, receiver);
                    }
                } else {
                    setPropertyGenericEvaluatedStringOrSymbol(targetObject, objIndex, value, receiver);
                }
            } else {
                setPropertyGeneric(targetObject, index, value, receiver);
            }
        }

        private Object toArrayIndex(Object index) {
            if (toArrayIndexNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                toArrayIndexNode = insert(ToArrayIndexNode.create());
            }
            return toArrayIndexNode.execute(index);
        }

        private ArrayWriteElementCacheNode getArrayWriteElementNode() {
            if (arrayWriteElementNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                arrayWriteElementNode = insert(ArrayWriteElementCacheNode.create(context, isStrict, writeOwn));
            }
            return arrayWriteElementNode;
        }

        @Override
        protected void executeWithTargetAndIndexUnguarded(Object target, int index, Object value, Object receiver) {
            DynamicObject targetObject = JSObject.castJSObject(target);
            boolean arrayCondition = isArrayNode.execute(targetObject);
            if (arrayProfile.profile(arrayCondition)) {
                ScriptArray array = JSObject.getArray(targetObject, arrayCondition);

                if (intOrStringIndexProfile.profile(index >= 0)) {
                    if (!getArrayWriteElementNode().executeWithTargetAndArrayAndIndexAndValue(targetObject, array, index, value, arrayCondition)) {
                        setPropertyGenericEvaluatedIndex(targetObject, index, value, receiver);
                    }
                } else {
                    setPropertyGenericEvaluatedStringOrSymbol(targetObject, Boundaries.stringValueOf(index), value, receiver);
                }
            } else {
                setPropertyGeneric(targetObject, index, value, receiver);
            }
        }

        private void setPropertyGenericEvaluatedIndex(DynamicObject targetObject, long index, Object value, Object receiver) {
            JSObject.setWithReceiver(targetObject, index, value, receiver, isStrict, jsclassProfile);
        }

        private void setPropertyGenericEvaluatedStringOrSymbol(DynamicObject targetObject, Object key, Object value, Object receiver) {
            JSObject.setWithReceiver(targetObject, key, value, receiver, isStrict, jsclassProfile);
        }

        private void setPropertyGeneric(DynamicObject targetObject, Object index, Object value, Object receiver) {
            setCachedProperty(targetObject, index, value, receiver);
        }

        private void setCachedProperty(DynamicObject targetObject, Object index, Object value, Object receiver) {
            if (setPropertyCachedNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                setPropertyCachedNode = insert(CachedSetPropertyNode.create(context, isStrict, writeOwn));
            }
            setPropertyCachedNode.execute(targetObject, index, value, receiver);
        }

        @Override
        public boolean guard(Object target) {
            return isObjectNode.executeBoolean(target);
        }
    }

    private static class JavaObjectWriteElementTypeCacheNode extends CachedWriteElementTypeCacheNode {
        protected final Class<?> targetClass;

        JavaObjectWriteElementTypeCacheNode(JSContext context, boolean isStrict, Class<?> targetClass, boolean writeOwn) {
            super(context, isStrict, writeOwn);
            this.targetClass = targetClass;
        }

        @Override
        protected void executeWithTargetAndIndexUnguarded(Object target, Object index, Object value, Object receiver) {
        }

        @Override
        protected void executeWithTargetAndIndexUnguarded(Object target, int index, Object value, Object receiver) {
        }

        @Override
        public final boolean guard(Object target) {
            return targetClass.isInstance(target);
        }
    }

    abstract static class ArrayWriteElementCacheNode extends WriteElementCacheNode {
        ArrayWriteElementCacheNode(JSContext context, boolean isStrict, boolean writeOwn) {
            super(context, isStrict, writeOwn);
        }

        static ArrayWriteElementCacheNode create(JSContext context, boolean isStrict, boolean writeOwn) {
            return new UninitArrayWriteElementCacheNode(context, isStrict, writeOwn, false);
        }

        protected abstract boolean executeWithTargetAndArrayAndIndexAndValue(DynamicObject target, ScriptArray array, long index, Object value, boolean arrayCondition);
    }

    private static class UninitArrayWriteElementCacheNode extends ArrayWriteElementCacheNode {
        private final boolean recursive;

        UninitArrayWriteElementCacheNode(JSContext context, boolean isStrict, boolean writeOwn, boolean recursive) {
            super(context, isStrict, writeOwn);
            this.recursive = recursive;
        }

        @Override
        protected boolean executeWithTargetAndArrayAndIndexAndValue(DynamicObject target, ScriptArray array, long index, Object value, boolean arrayCondition) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            ArrayWriteElementCacheNode selection;
            if (!JSSlowArray.isJSSlowArray(target) && !JSSlowArgumentsObject.isJSSlowArgumentsObject(target)) {
                selection = getSelection(array);
            } else {
                selection = new ExactArrayWriteElementCacheNode(context, isStrict, array, writeOwn, this);
            }
            Lock lock = getLock();
            try {
                lock.lock();
                purgeStaleCacheEntries(target);
                this.replace(selection);
                checkForPolymorphicSpecialize();
            } finally {
                lock.unlock();
            }
            return selection.executeWithTargetAndArrayAndIndexAndValue(target, array, index, value, false);
        }

        private void checkForPolymorphicSpecialize() {
            Node parent = getParent();
            if (parent != null && parent instanceof WriteElementCacheNode) {
                reportPolymorphicSpecialize();
            }
        }

        private ArrayWriteElementCacheNode getSelection(ScriptArray array) {
            UninitArrayWriteElementCacheNode next = this;
            if (array.isLengthNotWritable() || !array.isExtensible()) {
                // TODO handle this case in the specializations below
                return new ExactArrayWriteElementCacheNode(context, isStrict, array, writeOwn, next);
            }
            if (array instanceof LazyRegexResultArray) {
                return new LazyRegexResultArrayWriteElementCacheNode(context, isStrict, array, writeOwn, next);
            } else if (array instanceof AbstractConstantArray) {
                return new ConstantArrayWriteElementCacheNode(context, isStrict, array, writeOwn, next);
            } else if (array instanceof HolesIntArray) {
                return new HolesIntArrayWriteElementCacheNode(context, isStrict, array, writeOwn, next);
            } else if (array instanceof HolesDoubleArray) {
                return new HolesDoubleArrayWriteElementCacheNode(context, isStrict, array, writeOwn, next);
            } else if (array instanceof HolesJSObjectArray) {
                return new HolesJSObjectArrayWriteElementCacheNode(context, isStrict, array, writeOwn, next);
            } else if (array instanceof HolesObjectArray) {
                return new HolesObjectArrayWriteElementCacheNode(context, isStrict, array, writeOwn, next);
            } else if (array instanceof AbstractIntArray) {
                return new IntArrayWriteElementCacheNode(context, isStrict, array, writeOwn, next);
            } else if (array instanceof AbstractDoubleArray) {
                return new DoubleArrayWriteElementCacheNode(context, isStrict, array, writeOwn, next);
            } else if (array instanceof AbstractObjectArray) {
                return new ObjectArrayWriteElementCacheNode(context, isStrict, array, writeOwn, next);
            } else if (array instanceof AbstractJSObjectArray) {
                return new JSObjectArrayWriteElementCacheNode(context, isStrict, array, writeOwn, next);
            } else if (array instanceof AbstractWritableArray) {
                return new WritableArrayWriteElementCacheNode(context, isStrict, array, writeOwn, next);
            } else if (array instanceof TypedArray) {
                if (array instanceof TypedArray.AbstractUint32Array) {
                    return new Uint32ArrayWriteElementCacheNode(context, isStrict, array, writeOwn, next);
                } else if (array instanceof TypedArray.AbstractUint8ClampedArray) {
                    return new Uint8ClampedArrayWriteElementCacheNode(context, isStrict, array, writeOwn, next);
                } else if (array instanceof TypedIntArray) {
                    return new TypedIntArrayWriteElementCacheNode(context, isStrict, array, writeOwn, next);
                } else if (array instanceof TypedFloatArray) {
                    return new TypedFloatArrayWriteElementCacheNode(context, isStrict, array, writeOwn, next);
                } else if (array instanceof TypedBigIntArray) {
                    return new TypedBigIntArrayWriteElementCacheNode(context, isStrict, array, writeOwn, next);
                } else {
                    throw Errors.shouldNotReachHere();
                }
            } else {
                return new ExactArrayWriteElementCacheNode(context, isStrict, array, writeOwn, next);
            }
        }

        private void purgeStaleCacheEntries(DynamicObject target) {
            if (JSTruffleOptions.TrackArrayAllocationSites && !recursive && this.getParent() instanceof ConstantArrayWriteElementCacheNode && JSArray.isJSArray(target)) {
                ArrayAllocationSite allocationSite = arrayGetAllocationSite(target);
                if (allocationSite != null && allocationSite.getInitialArrayType() != null) {
                    ScriptArray initialArrayType = allocationSite.getInitialArrayType();
                    ConstantArrayWriteElementCacheNode existingNode = (ConstantArrayWriteElementCacheNode) this.getParent();
                    if (!(initialArrayType instanceof ConstantEmptyArray) && existingNode.getArrayType() instanceof ConstantEmptyArray) {
                        // allocation site has been patched to not create an empty array;
                        // purge existing empty array specialization in cache
                        if (JSTruffleOptions.TraceArrayTransitions) {
                            System.out.println("purging " + existingNode + arrayGetArrayType(target));
                        }
                        existingNode.purge();
                    }
                }
            }
        }
    }

    private abstract static class CachedArrayWriteElementCacheNode extends ArrayWriteElementCacheNode {
        @Child private ArrayWriteElementCacheNode arrayCacheNext;

        CachedArrayWriteElementCacheNode(JSContext context, boolean isStrict, boolean writeOwn, ArrayWriteElementCacheNode arrayCacheNext) {
            super(context, isStrict, writeOwn);
            this.arrayCacheNext = arrayCacheNext;

        }

        @Override
        protected boolean executeWithTargetAndArrayAndIndexAndValue(DynamicObject target, ScriptArray array, long index, Object value, boolean arrayCondition) {
            if (guard(target, array)) {
                return executeWithTargetAndArrayAndIndexAndValueUnguarded(target, array, index, value, arrayCondition);
            } else {
                return arrayCacheNext.executeWithTargetAndArrayAndIndexAndValue(target, array, index, value, arrayCondition);
            }
        }

        protected abstract boolean executeWithTargetAndArrayAndIndexAndValueUnguarded(DynamicObject target, ScriptArray array, long index, Object value, boolean arrayCondition);

        protected abstract boolean guard(Object target, ScriptArray array);

        protected final void purge() {
            this.replace(arrayCacheNext);
        }
    }

    private abstract static class ArrayClassGuardCachedArrayWriteElementCacheNode extends CachedArrayWriteElementCacheNode {
        private final ScriptArray arrayType;

        ArrayClassGuardCachedArrayWriteElementCacheNode(JSContext context, boolean isStrict, ScriptArray arrayType, boolean writeOwn, ArrayWriteElementCacheNode arrayCacheNext) {
            super(context, isStrict, writeOwn, arrayCacheNext);
            this.arrayType = arrayType;
        }

        @Override
        protected final boolean guard(Object target, ScriptArray array) {
            return arrayType.isInstance(array);
        }

        protected final ScriptArray cast(ScriptArray array) {
            return arrayType.cast(array);
        }

        protected final ScriptArray getArrayType() {
            return arrayType;
        }

        protected void checkDetachedArrayBuffer(DynamicObject target) {
            if (JSArrayBufferView.hasDetachedBuffer(target, context)) {
                throw Errors.createTypeErrorDetachedBuffer();
            }
        }
    }

    private abstract static class RecursiveCachedArrayWriteElementCacheNode extends ArrayClassGuardCachedArrayWriteElementCacheNode {
        @Child private ArrayWriteElementCacheNode recursiveWrite;
        private final BranchProfile needPrototypeBranch = BranchProfile.create();

        RecursiveCachedArrayWriteElementCacheNode(JSContext context, boolean isStrict, ScriptArray arrayType, boolean writeOwn, ArrayWriteElementCacheNode arrayCacheNext) {
            super(context, isStrict, arrayType, writeOwn, arrayCacheNext);
        }

        protected final boolean setArrayAndWrite(ScriptArray newArray, DynamicObject target, long index, Object value, boolean arrayCondition) {
            arraySetArrayType(target, newArray);
            if (recursiveWrite == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                this.recursiveWrite = insert(ArrayWriteElementCacheNode.create(context, isStrict, writeOwn));
            }
            return recursiveWrite.executeWithTargetAndArrayAndIndexAndValue(target, newArray, index, value, arrayCondition);
        }

        protected final boolean nonHolesArrayNeedsSlowSet(DynamicObject target, AbstractWritableArray arrayType, long index, boolean arrayCondition) {
            assert !arrayType.isHolesType();
            if (!context.getArrayPrototypeNoElementsAssumption().isValid() && !writeOwn) {
                if (!arrayType.hasElement(target, index, arrayCondition) && JSObject.hasProperty(target, index)) {
                    needPrototypeBranch.enter();
                    return true;
                }
            }
            return false;
        }

        protected final boolean holesArrayNeedsSlowSet(DynamicObject target, AbstractWritableArray arrayType, long index, boolean arrayCondition) {
            assert arrayType.isHolesType();
            if ((!context.getArrayPrototypeNoElementsAssumption().isValid() && !writeOwn) ||
                            (!context.getFastArrayAssumption().isValid() && JSSlowArray.isJSSlowArray(target)) ||
                            (!context.getFastArgumentsObjectAssumption().isValid() && JSSlowArgumentsObject.isJSSlowArgumentsObject(target))) {
                if (!arrayType.hasElement(target, index, arrayCondition) && JSObject.hasProperty(target, index)) {
                    needPrototypeBranch.enter();
                    return true;
                }
            }
            return false;
        }
    }

    private static class ExactArrayWriteElementCacheNode extends ArrayClassGuardCachedArrayWriteElementCacheNode {

        ExactArrayWriteElementCacheNode(JSContext context, boolean isStrict, ScriptArray arrayType, boolean writeOwn, ArrayWriteElementCacheNode arrayCacheNext) {
            super(context, isStrict, arrayType, writeOwn, arrayCacheNext);
        }

        @Override
        protected boolean executeWithTargetAndArrayAndIndexAndValueUnguarded(DynamicObject target, ScriptArray array, long index, Object value, boolean arrayCondition) {
            return false;
        }
    }

    private static class LazyRegexResultArrayWriteElementCacheNode extends RecursiveCachedArrayWriteElementCacheNode {

        private final ConditionProfile inBoundsProfile = ConditionProfile.createBinaryProfile();

        @Child private TRegexUtil.TRegexMaterializeResultNode materializeResultNode = TRegexUtil.TRegexMaterializeResultNode.create();

        LazyRegexResultArrayWriteElementCacheNode(JSContext context, boolean isStrict, ScriptArray arrayType, boolean writeOwn, ArrayWriteElementCacheNode arrayCacheNext) {
            super(context, isStrict, arrayType, writeOwn, arrayCacheNext);
        }

        @Override
        protected boolean executeWithTargetAndArrayAndIndexAndValueUnguarded(DynamicObject target, ScriptArray array, long index, Object value, boolean arrayCondition) {
            LazyRegexResultArray lazyRegexResultArray = (LazyRegexResultArray) cast(array);
            ScriptArray newArray = lazyRegexResultArray.createWritable(materializeResultNode, target, index, value, arrayCondition);
            if (inBoundsProfile.profile(index >= 0 && index < 0x7fff_ffff)) {
                return setArrayAndWrite(newArray, target, index, value, arrayCondition);
            } else {
                arraySetArrayType(target, SparseArray.makeSparseArray(target, newArray).setElement(target, index, value, isStrict, arrayCondition));
                return true;
            }
        }
    }

    private static class ConstantArrayWriteElementCacheNode extends RecursiveCachedArrayWriteElementCacheNode {
        private final ConditionProfile inBoundsProfile = ConditionProfile.createBinaryProfile();
        private final BranchProfile inBoundsIntBranch = BranchProfile.create();
        private final BranchProfile inBoundsDoubleBranch = BranchProfile.create();
        private final BranchProfile inBoundsJSObjectBranch = BranchProfile.create();
        private final BranchProfile inBoundsObjectBranch = BranchProfile.create();
        private final ScriptArray.ProfileHolder createWritableProfile = AbstractConstantArray.createCreateWritableProfile();

        ConstantArrayWriteElementCacheNode(JSContext context, boolean isStrict, ScriptArray arrayType, boolean writeOwn, ArrayWriteElementCacheNode arrayCacheNext) {
            super(context, isStrict, arrayType, writeOwn, arrayCacheNext);
        }

        @Override
        protected boolean executeWithTargetAndArrayAndIndexAndValueUnguarded(DynamicObject target, ScriptArray array, long index, Object value, boolean arrayCondition) {
            AbstractConstantArray constantArray = (AbstractConstantArray) cast(array);
            if (inBoundsProfile.profile(index >= 0 && index < 0x7fff_ffff)) {
                ScriptArray newArray;
                if (value instanceof Integer) {
                    inBoundsIntBranch.enter();
                    newArray = constantArray.createWriteableInt(target, index, (int) value, arrayCondition, createWritableProfile);
                } else if (value instanceof Double) {
                    inBoundsDoubleBranch.enter();
                    newArray = constantArray.createWriteableDouble(target, index, (double) value, arrayCondition, createWritableProfile);
                } else if (JSObject.isDynamicObject(value)) {
                    inBoundsJSObjectBranch.enter();
                    newArray = constantArray.createWriteableJSObject(target, index, (DynamicObject) value, arrayCondition, createWritableProfile);
                } else {
                    inBoundsObjectBranch.enter();
                    newArray = constantArray.createWriteableObject(target, index, value, arrayCondition, createWritableProfile);
                }
                return setArrayAndWrite(newArray, target, index, value, arrayCondition);
            } else {
                arraySetArrayType(target, SparseArray.makeSparseArray(target, array).setElement(target, index, value, isStrict, arrayCondition));
                return true;
            }
        }
    }

    private static class WritableArrayWriteElementCacheNode extends ArrayClassGuardCachedArrayWriteElementCacheNode {
        private final ConditionProfile inBoundsProfile = ConditionProfile.createBinaryProfile();

        WritableArrayWriteElementCacheNode(JSContext context, boolean isStrict, ScriptArray arrayType, boolean writeOwn, ArrayWriteElementCacheNode arrayCacheNext) {
            super(context, isStrict, arrayType, writeOwn, arrayCacheNext);
        }

        @Override
        protected boolean executeWithTargetAndArrayAndIndexAndValueUnguarded(DynamicObject target, ScriptArray array, long index, Object value, boolean arrayCondition) {
            AbstractWritableArray writableArray = (AbstractWritableArray) cast(array);
            if (inBoundsProfile.profile(writableArray.isInBoundsFast(target, index, arrayCondition))) {
                arraySetArrayType(target, writableArray.setElement(target, index, value, isStrict, arrayCondition));
                return true;
            } else {
                return false;
            }
        }
    }

    private static class IntArrayWriteElementCacheNode extends RecursiveCachedArrayWriteElementCacheNode {
        private final BranchProfile intValueBranch = BranchProfile.create();
        private final BranchProfile toDoubleBranch = BranchProfile.create();
        private final BranchProfile toObjectBranch = BranchProfile.create();
        private final ConditionProfile inBoundsFastCondition = ConditionProfile.createBinaryProfile();
        private final ConditionProfile inBoundsCondition = ConditionProfile.createBinaryProfile();
        private final ConditionProfile supportedNonZeroCondition = ConditionProfile.createBinaryProfile();
        private final ConditionProfile supportedZeroCondition = ConditionProfile.createBinaryProfile();
        private final ConditionProfile supportedContiguousCondition = ConditionProfile.createBinaryProfile();
        private final ConditionProfile supportedHolesCondition = ConditionProfile.createBinaryProfile();
        private final ScriptArray.ProfileHolder profile = AbstractWritableArray.createSetSupportedProfile();

        IntArrayWriteElementCacheNode(JSContext context, boolean isStrict, ScriptArray arrayType, boolean writeOwn, ArrayWriteElementCacheNode arrayCacheNext) {
            super(context, isStrict, arrayType, writeOwn, arrayCacheNext);
        }

        @Override
        protected boolean executeWithTargetAndArrayAndIndexAndValueUnguarded(DynamicObject target, ScriptArray array, long index, Object value, boolean arrayCondition) {
            AbstractIntArray intArray = (AbstractIntArray) cast(array);
            if (value instanceof Integer) {
                intValueBranch.enter();
                return executeWithIntValueInner(target, intArray, index, (int) value, arrayCondition);
            } else if (value instanceof Double) {
                toDoubleBranch.enter();
                double doubleValue = (double) value;
                return setArrayAndWrite(intArray.toDouble(target, index, doubleValue, arrayCondition), target, index, doubleValue, arrayCondition);
            } else {
                toObjectBranch.enter();
                return setArrayAndWrite(intArray.toObject(target, index, value, arrayCondition), target, index, value, arrayCondition);
            }
        }

        private boolean executeWithIntValueInner(DynamicObject target, AbstractIntArray intArray, long index, int intValue, boolean arrayCondition) {
            assert !(intArray instanceof HolesIntArray);
            if (nonHolesArrayNeedsSlowSet(target, intArray, index, arrayCondition)) {
                return false;
            }
            int iIndex = (int) index;
            if (inBoundsFastCondition.profile(intArray.isInBoundsFast(target, index, arrayCondition) && !mightTransferToNonContiguous(intArray, index))) {
                intArray.setInBoundsFast(target, iIndex, intValue, arrayCondition);
                return true;
            } else if (inBoundsCondition.profile(intArray.isInBounds(target, iIndex, arrayCondition) && !mightTransferToNonContiguous(intArray, index))) {
                intArray.setInBounds(target, iIndex, intValue, arrayCondition, profile);
                return true;
            } else {
                if (supportedNonZeroCondition.profile(intArray.isSupported(target, index, arrayCondition) && !mightTransferToNonContiguous(intArray, index))) {
                    intArray.setSupported(target, iIndex, intValue, arrayCondition, profile);
                    return true;
                } else if (supportedZeroCondition.profile(mightTransferToNonContiguous(intArray, index) && intArray.isSupported(target, index, arrayCondition))) {
                    return setArrayAndWrite(intArray.toNonContiguous(target, iIndex, intValue, arrayCondition, profile), target, index, intValue, arrayCondition);
                } else if (supportedContiguousCondition.profile(!(intArray instanceof AbstractContiguousIntArray) && intArray.isSupportedContiguous(target, index, arrayCondition))) {
                    return setArrayAndWrite(intArray.toContiguous(target, index, intValue, arrayCondition), target, index, intValue, arrayCondition);
                } else if (supportedHolesCondition.profile(intArray.isSupportedHoles(target, index, arrayCondition))) {
                    return setArrayAndWrite(intArray.toHoles(target, index, intValue, arrayCondition), target, index, intValue, arrayCondition);
                } else {
                    assert intArray.isSparse(target, index, arrayCondition);
                    return setArrayAndWrite(intArray.toSparse(target, index, intValue), target, index, intValue, arrayCondition);
                }
            }
        }

        private static boolean mightTransferToNonContiguous(AbstractIntArray intArray, long index) {
            return intArray instanceof ContiguousIntArray && index == 0;
        }
    }

    private static class DoubleArrayWriteElementCacheNode extends RecursiveCachedArrayWriteElementCacheNode {
        private final BranchProfile intValueBranch = BranchProfile.create();
        private final BranchProfile doubleValueBranch = BranchProfile.create();
        private final BranchProfile toObjectBranch = BranchProfile.create();
        private final ConditionProfile inBoundsFastCondition = ConditionProfile.createBinaryProfile();
        private final ConditionProfile inBoundsCondition = ConditionProfile.createBinaryProfile();
        private final ConditionProfile supportedCondition = ConditionProfile.createBinaryProfile();
        private final ConditionProfile supportedContiguousCondition = ConditionProfile.createBinaryProfile();
        private final ConditionProfile supportedHolesCondition = ConditionProfile.createBinaryProfile();
        private final ScriptArray.ProfileHolder profile = AbstractWritableArray.createSetSupportedProfile();

        DoubleArrayWriteElementCacheNode(JSContext context, boolean isStrict, ScriptArray arrayType, boolean writeOwn, ArrayWriteElementCacheNode arrayCacheNext) {
            super(context, isStrict, arrayType, writeOwn, arrayCacheNext);
        }

        @Override
        protected boolean executeWithTargetAndArrayAndIndexAndValueUnguarded(DynamicObject target, ScriptArray array, long index, Object value, boolean arrayCondition) {
            AbstractDoubleArray doubleArray = (AbstractDoubleArray) cast(array);
            double doubleValue;
            if (value instanceof Double) {
                doubleValueBranch.enter();
                doubleValue = (double) value;
            } else if (value instanceof Integer) {
                intValueBranch.enter();
                doubleValue = (int) value;
            } else {
                toObjectBranch.enter();
                return setArrayAndWrite(doubleArray.toObject(target, index, value, arrayCondition), target, index, value, arrayCondition);
            }
            return executeWithDoubleValueInner(target, doubleArray, index, doubleValue, arrayCondition);
        }

        private boolean executeWithDoubleValueInner(DynamicObject target, AbstractDoubleArray doubleArray, long index, double doubleValue, boolean arrayCondition) {
            assert !(doubleArray instanceof HolesDoubleArray);
            if (nonHolesArrayNeedsSlowSet(target, doubleArray, index, arrayCondition)) {
                return false;
            }
            int iIndex = (int) index;
            if (inBoundsFastCondition.profile(doubleArray.isInBoundsFast(target, index, arrayCondition))) {
                doubleArray.setInBoundsFast(target, iIndex, doubleValue, arrayCondition);
                return true;
            } else if (inBoundsCondition.profile(doubleArray.isInBounds(target, iIndex, arrayCondition))) {
                doubleArray.setInBounds(target, iIndex, doubleValue, arrayCondition, profile);
                return true;
            } else {
                if (supportedCondition.profile(doubleArray.isSupported(target, index, arrayCondition))) {
                    doubleArray.setSupported(target, iIndex, doubleValue, arrayCondition, profile);
                    return true;
                } else if (supportedContiguousCondition.profile(!(doubleArray instanceof AbstractContiguousDoubleArray) && doubleArray.isSupportedContiguous(target, index, arrayCondition))) {
                    return setArrayAndWrite(doubleArray.toContiguous(target, index, doubleValue, arrayCondition), target, index, doubleValue, arrayCondition);
                } else if (supportedHolesCondition.profile(doubleArray.isSupportedHoles(target, index, arrayCondition))) {
                    return setArrayAndWrite(doubleArray.toHoles(target, index, doubleValue, arrayCondition), target, index, doubleValue, arrayCondition);
                } else {
                    assert doubleArray.isSparse(target, index, arrayCondition);
                    return setArrayAndWrite(doubleArray.toSparse(target, index, doubleValue), target, index, doubleValue, arrayCondition);
                }
            }
        }
    }

    private static class ObjectArrayWriteElementCacheNode extends RecursiveCachedArrayWriteElementCacheNode {
        private final ConditionProfile inBoundsFastCondition = ConditionProfile.createBinaryProfile();
        private final ConditionProfile inBoundsCondition = ConditionProfile.createBinaryProfile();
        private final ConditionProfile supportedCondition = ConditionProfile.createBinaryProfile();
        private final ConditionProfile supportedContiguousCondition = ConditionProfile.createBinaryProfile();
        private final ConditionProfile supportedHolesCondition = ConditionProfile.createBinaryProfile();
        private final ScriptArray.ProfileHolder profile = AbstractWritableArray.createSetSupportedProfile();

        ObjectArrayWriteElementCacheNode(JSContext context, boolean isStrict, ScriptArray arrayType, boolean writeOwn, ArrayWriteElementCacheNode arrayCacheNext) {
            super(context, isStrict, arrayType, writeOwn, arrayCacheNext);
        }

        @Override
        protected boolean executeWithTargetAndArrayAndIndexAndValueUnguarded(DynamicObject target, ScriptArray array, long index, Object value, boolean arrayCondition) {
            AbstractObjectArray objectArray = (AbstractObjectArray) cast(array);
            assert !(objectArray instanceof HolesObjectArray);
            if (nonHolesArrayNeedsSlowSet(target, objectArray, index, arrayCondition)) {
                return false;
            }
            int iIndex = (int) index;
            if (inBoundsFastCondition.profile(objectArray.isInBoundsFast(target, index, arrayCondition))) {
                objectArray.setInBoundsFast(target, iIndex, value, arrayCondition);
                return true;
            } else if (inBoundsCondition.profile(objectArray.isInBounds(target, iIndex, arrayCondition))) {
                objectArray.setInBounds(target, iIndex, value, arrayCondition, profile);
                return true;
            } else if (supportedCondition.profile(objectArray.isSupported(target, index, arrayCondition))) {
                objectArray.setSupported(target, iIndex, value, arrayCondition);
                return true;
            } else if (supportedContiguousCondition.profile(!(objectArray instanceof AbstractContiguousObjectArray) && objectArray.isSupportedContiguous(target, index, arrayCondition))) {
                return setArrayAndWrite(objectArray.toContiguous(target, index, value, arrayCondition), target, index, value, arrayCondition);
            } else if (supportedHolesCondition.profile(objectArray.isSupportedHoles(target, index, arrayCondition))) {
                return setArrayAndWrite(objectArray.toHoles(target, index, value, arrayCondition), target, index, value, arrayCondition);
            } else {
                assert objectArray.isSparse(target, index, arrayCondition) : objectArray.getClass() + " " + objectArray.firstElementIndex(target) + "-" + objectArray.lastElementIndex(target) + " / " +
                                index;
                return setArrayAndWrite(objectArray.toSparse(target, index, value), target, index, value, arrayCondition);
            }
        }
    }

    private static class JSObjectArrayWriteElementCacheNode extends RecursiveCachedArrayWriteElementCacheNode {
        private final ConditionProfile objectType = ConditionProfile.createBinaryProfile();
        private final ConditionProfile inBoundsFastCondition = ConditionProfile.createBinaryProfile();
        private final ConditionProfile inBoundsCondition = ConditionProfile.createBinaryProfile();
        private final ConditionProfile supportedCondition = ConditionProfile.createBinaryProfile();
        private final ConditionProfile supportedContiguousCondition = ConditionProfile.createBinaryProfile();
        private final ConditionProfile supportedHolesCondition = ConditionProfile.createBinaryProfile();
        private final ScriptArray.ProfileHolder profile = AbstractWritableArray.createSetSupportedProfile();

        JSObjectArrayWriteElementCacheNode(JSContext context, boolean isStrict, ScriptArray arrayType, boolean writeOwn, ArrayWriteElementCacheNode arrayCacheNext) {
            super(context, isStrict, arrayType, writeOwn, arrayCacheNext);
        }

        @Override
        protected boolean executeWithTargetAndArrayAndIndexAndValueUnguarded(DynamicObject target, ScriptArray array, long index, Object value, boolean arrayCondition) {
            AbstractJSObjectArray jsobjectArray = (AbstractJSObjectArray) cast(array);
            if (objectType.profile(JSObject.isDynamicObject(value))) {
                DynamicObject jsobjectValue = (DynamicObject) value;
                return executeWithJSObjectValueInner(target, jsobjectArray, index, jsobjectValue, arrayCondition);
            } else {
                return setArrayAndWrite(jsobjectArray.toObject(target, index, value, arrayCondition), target, index, value, arrayCondition);
            }
        }

        private boolean executeWithJSObjectValueInner(DynamicObject target, AbstractJSObjectArray jsobjectArray, long index, DynamicObject jsobjectValue, boolean arrayCondition) {
            assert !(jsobjectArray instanceof HolesJSObjectArray);
            int iIndex = (int) index;
            if (nonHolesArrayNeedsSlowSet(target, jsobjectArray, index, arrayCondition)) {
                return false;
            }
            if (inBoundsFastCondition.profile(jsobjectArray.isInBoundsFast(target, index, arrayCondition))) {
                jsobjectArray.setInBoundsFast(target, iIndex, jsobjectValue, arrayCondition);
                return true;
            } else if (inBoundsCondition.profile(jsobjectArray.isInBounds(target, iIndex, arrayCondition))) {
                jsobjectArray.setInBounds(target, iIndex, jsobjectValue, arrayCondition, profile);
                return true;
            } else if (supportedCondition.profile(jsobjectArray.isSupported(target, index, arrayCondition))) {
                jsobjectArray.setSupported(target, iIndex, jsobjectValue, arrayCondition, profile);
                return true;
            } else if (supportedContiguousCondition.profile(!(jsobjectArray instanceof AbstractContiguousJSObjectArray) && jsobjectArray.isSupportedContiguous(target, index, arrayCondition))) {
                return setArrayAndWrite(jsobjectArray.toContiguous(target, index, jsobjectValue, arrayCondition), target, index, jsobjectValue, arrayCondition);
            } else if (supportedHolesCondition.profile(jsobjectArray.isSupportedHoles(target, index, arrayCondition))) {
                return setArrayAndWrite(jsobjectArray.toHoles(target, index, jsobjectValue, arrayCondition), target, index, jsobjectValue, arrayCondition);
            } else {
                assert jsobjectArray.isSparse(target, index, arrayCondition);
                return setArrayAndWrite(jsobjectArray.toSparse(target, index, jsobjectValue), target, index, jsobjectValue, arrayCondition);
            }
        }
    }

    private static class HolesIntArrayWriteElementCacheNode extends RecursiveCachedArrayWriteElementCacheNode {
        private final BranchProfile intValueBranch = BranchProfile.create();
        private final BranchProfile toDoubleBranch = BranchProfile.create();
        private final BranchProfile toObjectBranch = BranchProfile.create();
        private final ConditionProfile inBoundsFastCondition = ConditionProfile.createBinaryProfile();
        private final ConditionProfile inBoundsFastHoleCondition = ConditionProfile.createBinaryProfile();
        private final ConditionProfile inBoundsCondition = ConditionProfile.createBinaryProfile();
        private final ConditionProfile supportedContainsHolesCondition = ConditionProfile.createBinaryProfile();
        private final ConditionProfile supportedNotContainsHolesCondition = ConditionProfile.createBinaryProfile();
        private final ConditionProfile hasExplicitHolesProfile = ConditionProfile.createBinaryProfile();
        private final ConditionProfile containsHolesProfile = ConditionProfile.createBinaryProfile();
        private final ScriptArray.ProfileHolder profile = AbstractWritableArray.createSetSupportedProfile();

        HolesIntArrayWriteElementCacheNode(JSContext context, boolean isStrict, ScriptArray arrayType, boolean writeOwn, ArrayWriteElementCacheNode arrayCacheNext) {
            super(context, isStrict, arrayType, writeOwn, arrayCacheNext);
        }

        @Override
        protected boolean executeWithTargetAndArrayAndIndexAndValueUnguarded(DynamicObject target, ScriptArray array, long index, Object value, boolean arrayCondition) {
            HolesIntArray holesIntArray = (HolesIntArray) cast(array);
            if (value instanceof Integer) {
                intValueBranch.enter();
                int intValue = (int) value;
                return executeWithIntValueInner(target, holesIntArray, index, intValue, arrayCondition);
            } else if (value instanceof Double) {
                toDoubleBranch.enter();
                double doubleValue = (double) value;
                return setArrayAndWrite(holesIntArray.toDouble(target, index, doubleValue, arrayCondition), target, index, doubleValue, arrayCondition);
            } else {
                toObjectBranch.enter();
                return setArrayAndWrite(holesIntArray.toObject(target, index, value, arrayCondition), target, index, value, arrayCondition);
            }
        }

        private boolean executeWithIntValueInner(DynamicObject target, HolesIntArray holesIntArray, long index, int intValue, boolean arrayCondition) {
            if (holesArrayNeedsSlowSet(target, holesIntArray, index, arrayCondition)) {
                return false;
            }
            int iIndex = (int) index;
            boolean containsHoles = containsHolesProfile.profile(containsHoles(target, holesIntArray, index, arrayCondition));
            if (containsHoles && inBoundsFastCondition.profile(holesIntArray.isInBoundsFast(target, index, arrayCondition) && !HolesIntArray.isHoleValue(intValue))) {
                if (inBoundsFastHoleCondition.profile(holesIntArray.isHoleFast(target, iIndex, arrayCondition))) {
                    holesIntArray.setInBoundsFastHole(target, iIndex, intValue, arrayCondition);
                } else {
                    holesIntArray.setInBoundsFastNonHole(target, iIndex, intValue, arrayCondition);
                }
                return true;
            } else if (containsHoles && inBoundsCondition.profile(holesIntArray.isInBounds(target, iIndex, arrayCondition) && !HolesIntArray.isHoleValue(intValue))) {
                holesIntArray.setInBounds(target, iIndex, intValue, arrayCondition, profile);
                return true;
            } else if (containsHoles && supportedContainsHolesCondition.profile(holesIntArray.isSupported(target, index, arrayCondition) && !HolesIntArray.isHoleValue(intValue))) {
                holesIntArray.setSupported(target, iIndex, intValue, arrayCondition, profile);
                return true;
            } else if (!containsHoles && supportedNotContainsHolesCondition.profile(holesIntArray.isSupported(target, index, arrayCondition))) {
                return setArrayAndWrite(holesIntArray.toNonHoles(target, index, intValue, arrayCondition), target, index, intValue, arrayCondition);
            } else {
                assert holesIntArray.isSparse(target, index, arrayCondition);
                return setArrayAndWrite(holesIntArray.toSparse(target, index, intValue), target, index, intValue, arrayCondition);
            }
        }

        private boolean containsHoles(DynamicObject target, HolesIntArray holesIntArray, long index, boolean condition) {
            return hasExplicitHolesProfile.profile(JSArray.arrayGetHoleCount(target, condition) > 0) || !holesIntArray.isInBoundsFast(target, index, condition);
        }
    }

    private static class HolesDoubleArrayWriteElementCacheNode extends RecursiveCachedArrayWriteElementCacheNode {
        private final BranchProfile doubleValueBranch = BranchProfile.create();
        private final BranchProfile intValueBranch = BranchProfile.create();
        private final BranchProfile toObjectBranch = BranchProfile.create();
        private final ConditionProfile inBoundsFastCondition = ConditionProfile.createBinaryProfile();
        private final ConditionProfile inBoundsFastHoleCondition = ConditionProfile.createBinaryProfile();
        private final ConditionProfile inBoundsCondition = ConditionProfile.createBinaryProfile();
        private final ConditionProfile supportedContainsHolesCondition = ConditionProfile.createBinaryProfile();
        private final ConditionProfile supportedNotContainsHolesCondition = ConditionProfile.createBinaryProfile();
        private final ConditionProfile hasExplicitHolesProfile = ConditionProfile.createBinaryProfile();
        private final ConditionProfile containsHolesProfile = ConditionProfile.createBinaryProfile();
        private final ScriptArray.ProfileHolder profile = AbstractWritableArray.createSetSupportedProfile();

        HolesDoubleArrayWriteElementCacheNode(JSContext context, boolean isStrict, ScriptArray arrayType, boolean writeOwn, ArrayWriteElementCacheNode arrayCacheNext) {
            super(context, isStrict, arrayType, writeOwn, arrayCacheNext);
        }

        @Override
        protected boolean executeWithTargetAndArrayAndIndexAndValueUnguarded(DynamicObject target, ScriptArray array, long index, Object value, boolean arrayCondition) {
            HolesDoubleArray holesDoubleArray = (HolesDoubleArray) cast(array);
            double doubleValue;
            if (value instanceof Double) {
                doubleValueBranch.enter();
                doubleValue = (double) value;
            } else if (value instanceof Integer) {
                intValueBranch.enter();
                doubleValue = (int) value;
            } else {
                toObjectBranch.enter();
                return setArrayAndWrite(holesDoubleArray.toObject(target, index, value, arrayCondition), target, index, value, arrayCondition);
            }

            return executeWithDoubleValueInner(target, holesDoubleArray, index, doubleValue, arrayCondition);
        }

        private boolean executeWithDoubleValueInner(DynamicObject target, HolesDoubleArray holesDoubleArray, long index, double doubleValue, boolean arrayCondition) {
            if (holesArrayNeedsSlowSet(target, holesDoubleArray, index, arrayCondition)) {
                return false;
            }
            int iIndex = (int) index;
            boolean containsHoles = containsHolesProfile.profile(containsHoles(target, holesDoubleArray, index, arrayCondition));
            if (containsHoles && inBoundsFastCondition.profile(holesDoubleArray.isInBoundsFast(target, index, arrayCondition) && !HolesDoubleArray.isHoleValue(doubleValue))) {
                if (inBoundsFastHoleCondition.profile(holesDoubleArray.isHoleFast(target, iIndex, arrayCondition))) {
                    holesDoubleArray.setInBoundsFastHole(target, iIndex, doubleValue, arrayCondition);
                } else {
                    holesDoubleArray.setInBoundsFastNonHole(target, iIndex, doubleValue, arrayCondition);
                }
                return true;
            } else if (containsHoles && inBoundsCondition.profile(holesDoubleArray.isInBounds(target, iIndex, arrayCondition) && !HolesDoubleArray.isHoleValue(doubleValue))) {
                holesDoubleArray.setInBounds(target, iIndex, doubleValue, arrayCondition, profile);
                return true;
            } else if (containsHoles && supportedContainsHolesCondition.profile(holesDoubleArray.isSupported(target, index, arrayCondition) && !HolesDoubleArray.isHoleValue(doubleValue))) {
                holesDoubleArray.setSupported(target, iIndex, doubleValue, arrayCondition, profile);
                return true;
            } else if (!containsHoles && supportedNotContainsHolesCondition.profile(holesDoubleArray.isSupported(target, index, arrayCondition))) {
                return setArrayAndWrite(holesDoubleArray.toNonHoles(target, index, doubleValue, arrayCondition), target, index, doubleValue, arrayCondition);
            } else {
                assert holesDoubleArray.isSparse(target, index, arrayCondition);
                return setArrayAndWrite(holesDoubleArray.toSparse(target, index, doubleValue), target, index, doubleValue, arrayCondition);
            }
        }

        private boolean containsHoles(DynamicObject target, HolesDoubleArray holesDoubleArray, long index, boolean condition) {
            return hasExplicitHolesProfile.profile(JSArray.arrayGetHoleCount(target, condition) > 0) || !holesDoubleArray.isInBoundsFast(target, index, condition);
        }
    }

    private static class HolesJSObjectArrayWriteElementCacheNode extends RecursiveCachedArrayWriteElementCacheNode {
        private final ConditionProfile objectType = ConditionProfile.createBinaryProfile();
        private final ConditionProfile inBoundsFastCondition = ConditionProfile.createBinaryProfile();
        private final ConditionProfile inBoundsFastHoleCondition = ConditionProfile.createBinaryProfile();
        private final ConditionProfile inBoundsCondition = ConditionProfile.createBinaryProfile();
        private final ConditionProfile supportedContainsHolesCondition = ConditionProfile.createBinaryProfile();
        private final ConditionProfile supportedNotContainsHolesCondition = ConditionProfile.createBinaryProfile();
        private final ConditionProfile hasExplicitHolesProfile = ConditionProfile.createBinaryProfile();
        private final ConditionProfile containsHolesProfile = ConditionProfile.createBinaryProfile();
        private final ScriptArray.ProfileHolder profile = AbstractWritableArray.createSetSupportedProfile();

        HolesJSObjectArrayWriteElementCacheNode(JSContext context, boolean isStrict, ScriptArray arrayType, boolean writeOwn, ArrayWriteElementCacheNode arrayCacheNext) {
            super(context, isStrict, arrayType, writeOwn, arrayCacheNext);
            assert arrayType.getClass() == HolesJSObjectArray.class;
        }

        @Override
        protected boolean executeWithTargetAndArrayAndIndexAndValueUnguarded(DynamicObject target, ScriptArray array, long index, Object value, boolean arrayCondition) {
            HolesJSObjectArray holesArray = (HolesJSObjectArray) cast(array);
            if (objectType.profile(JSObject.isDynamicObject(value))) {
                return executeWithJSObjectValueInner(target, holesArray, index, (DynamicObject) value, arrayCondition);
            } else {
                return setArrayAndWrite(holesArray.toObject(target, index, value, arrayCondition), target, index, value, arrayCondition);
            }
        }

        private boolean executeWithJSObjectValueInner(DynamicObject target, HolesJSObjectArray jsobjectArray, long index, DynamicObject value, boolean arrayCondition) {
            if (holesArrayNeedsSlowSet(target, jsobjectArray, index, arrayCondition)) {
                return false;
            }
            boolean containsHoles = containsHolesProfile.profile(containsHoles(target, jsobjectArray, index, arrayCondition));
            if (containsHoles && inBoundsFastCondition.profile(jsobjectArray.isInBoundsFast(target, index, arrayCondition))) {
                assert !HolesJSObjectArray.isHoleValue(value);
                if (inBoundsFastHoleCondition.profile(jsobjectArray.isHoleFast(target, (int) index, arrayCondition))) {
                    jsobjectArray.setInBoundsFastHole(target, (int) index, value, arrayCondition);
                } else {
                    jsobjectArray.setInBoundsFastNonHole(target, (int) index, value, arrayCondition);
                }
                return true;
            } else if (containsHoles && inBoundsCondition.profile(jsobjectArray.isInBounds(target, (int) index, arrayCondition))) {
                assert !HolesJSObjectArray.isHoleValue(value);
                jsobjectArray.setInBounds(target, (int) index, value, arrayCondition, profile);
                return true;
            } else if (containsHoles && supportedContainsHolesCondition.profile(jsobjectArray.isSupported(target, index, arrayCondition))) {
                assert !HolesJSObjectArray.isHoleValue(value);
                jsobjectArray.setSupported(target, (int) index, value, arrayCondition, profile);
                return true;
            } else if (!containsHoles && supportedNotContainsHolesCondition.profile(jsobjectArray.isSupported(target, index, arrayCondition))) {
                return setArrayAndWrite(jsobjectArray.toNonHoles(target, index, value, arrayCondition), target, index, value, arrayCondition);
            } else {
                assert jsobjectArray.isSparse(target, index, arrayCondition);
                return setArrayAndWrite(jsobjectArray.toSparse(target, index, value), target, index, value, arrayCondition);
            }
        }

        private boolean containsHoles(DynamicObject target, HolesJSObjectArray holesJSObjectArray, long index, boolean condition) {
            return hasExplicitHolesProfile.profile(JSArray.arrayGetHoleCount(target, condition) > 0) || !holesJSObjectArray.isInBoundsFast(target, index, condition);
        }
    }

    private static class HolesObjectArrayWriteElementCacheNode extends RecursiveCachedArrayWriteElementCacheNode {
        private final ConditionProfile inBoundsFastCondition = ConditionProfile.createBinaryProfile();
        private final ConditionProfile inBoundsFastHoleCondition = ConditionProfile.createBinaryProfile();
        private final ConditionProfile inBoundsCondition = ConditionProfile.createBinaryProfile();
        private final ConditionProfile supportedCondition = ConditionProfile.createBinaryProfile();
        private final ScriptArray.ProfileHolder profile = AbstractWritableArray.createSetSupportedProfile();

        HolesObjectArrayWriteElementCacheNode(JSContext context, boolean isStrict, ScriptArray arrayType, boolean writeOwn, ArrayWriteElementCacheNode arrayCacheNext) {
            super(context, isStrict, arrayType, writeOwn, arrayCacheNext);
            assert arrayType.getClass() == HolesObjectArray.class;
        }

        @Override
        protected boolean executeWithTargetAndArrayAndIndexAndValueUnguarded(DynamicObject target, ScriptArray array, long index, Object value, boolean arrayCondition) {
            HolesObjectArray objectArray = (HolesObjectArray) array;
            if (holesArrayNeedsSlowSet(target, objectArray, index, arrayCondition)) {
                return false;
            }
            if (inBoundsFastCondition.profile(objectArray.isInBoundsFast(target, index, arrayCondition))) {
                assert !HolesObjectArray.isHoleValue(value);
                if (inBoundsFastHoleCondition.profile(objectArray.isHoleFast(target, (int) index, arrayCondition))) {
                    objectArray.setInBoundsFastHole(target, (int) index, value, arrayCondition);
                } else {
                    objectArray.setInBoundsFastNonHole(target, (int) index, value, arrayCondition);
                }
                return true;
            } else if (inBoundsCondition.profile(objectArray.isInBounds(target, (int) index, arrayCondition))) {
                assert !HolesObjectArray.isHoleValue(value);
                objectArray.setInBounds(target, (int) index, value, arrayCondition, profile);
                return true;
            } else if (supportedCondition.profile(objectArray.isSupported(target, index, arrayCondition))) {
                assert !HolesObjectArray.isHoleValue(value);
                objectArray.setSupported(target, (int) index, value, arrayCondition);
                return true;
            } else {
                assert objectArray.isSparse(target, index, arrayCondition);
                return setArrayAndWrite(objectArray.toSparse(target, index, value), target, index, value, arrayCondition);
            }
        }
    }

    private abstract static class AbstractTypedIntArrayWriteElementCacheNode extends ArrayClassGuardCachedArrayWriteElementCacheNode {
        private final ConditionProfile inBoundsProfile = ConditionProfile.createBinaryProfile();

        AbstractTypedIntArrayWriteElementCacheNode(JSContext context, boolean isStrict, ScriptArray arrayType, boolean writeOwn, ArrayWriteElementCacheNode arrayCacheNext) {
            super(context, isStrict, arrayType, writeOwn, arrayCacheNext);
        }

        @Override
        protected final boolean executeWithTargetAndArrayAndIndexAndValueUnguarded(DynamicObject target, ScriptArray array, long index, Object value, boolean arrayCondition) {
            TypedIntArray<?> typedArray = (TypedIntArray<?>) cast(array);
            int iValue = toInt(value); // could throw
            checkDetachedArrayBuffer(target);
            if (inBoundsProfile.profile(typedArray.hasElement(target, index, arrayCondition))) {
                typedArray.setInt(target, (int) index, iValue, arrayCondition);
            } else {
                // do nothing; cf. ES6 9.4.5.9 IntegerIndexedElementSet(O, index, value)
            }
            return true;
        }

        protected abstract int toInt(Object value);
    }

    private static class TypedIntArrayWriteElementCacheNode extends AbstractTypedIntArrayWriteElementCacheNode {
        @Child private JSToInt32Node toIntNode;

        TypedIntArrayWriteElementCacheNode(JSContext context, boolean isStrict, ScriptArray arrayType, boolean writeOwn, ArrayWriteElementCacheNode arrayCacheNext) {
            super(context, isStrict, arrayType, writeOwn, arrayCacheNext);
            this.toIntNode = JSToInt32Node.create();
        }

        @Override
        protected int toInt(Object value) {
            return toIntNode.executeInt(value);
        }
    }

    private static class TypedBigIntArrayWriteElementCacheNode extends ArrayClassGuardCachedArrayWriteElementCacheNode {

        @Child private JSToBigIntNode toBigIntNode;
        private final ConditionProfile inBoundsProfile = ConditionProfile.createBinaryProfile();

        TypedBigIntArrayWriteElementCacheNode(JSContext context, boolean isStrict, ScriptArray arrayType, boolean writeOwn, ArrayWriteElementCacheNode arrayCacheNext) {
            super(context, isStrict, arrayType, writeOwn, arrayCacheNext);
            this.toBigIntNode = JSToBigIntNode.create();
        }

        @Override
        protected final boolean executeWithTargetAndArrayAndIndexAndValueUnguarded(DynamicObject target, ScriptArray array, long index, Object value, boolean arrayCondition) {
            TypedBigIntArray<?> typedArray = (TypedBigIntArray<?>) cast(array);
            BigInt biValue = toBigIntNode.executeBigInteger(value); // could throw
            checkDetachedArrayBuffer(target);
            if (inBoundsProfile.profile(typedArray.hasElement(target, index, arrayCondition))) {
                typedArray.setBigInt(target, (int) index, biValue, arrayCondition);
            }
            return true;
        }
    }

    private static class Uint8ClampedArrayWriteElementCacheNode extends AbstractTypedIntArrayWriteElementCacheNode {
        private final ConditionProfile toIntProfile = ConditionProfile.createBinaryProfile();
        @Child private JSToDoubleNode toDoubleNode;

        Uint8ClampedArrayWriteElementCacheNode(JSContext context, boolean isStrict, ScriptArray arrayType, boolean writeOwn, ArrayWriteElementCacheNode arrayCacheNext) {
            super(context, isStrict, arrayType, writeOwn, arrayCacheNext);
        }

        @Override
        protected int toInt(Object value) {
            if (toIntProfile.profile(value instanceof Integer)) {
                return (int) value;
            } else {
                double doubleValue = toDouble(value);
                return Uint8ClampedArray.toInt(doubleValue);
            }
        }

        private double toDouble(Object value) {
            if (toDoubleNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                toDoubleNode = insert(JSToDoubleNode.create());
            }
            return toDoubleNode.executeDouble(value);
        }
    }

    private static class Uint32ArrayWriteElementCacheNode extends AbstractTypedIntArrayWriteElementCacheNode {
        private final ConditionProfile toIntProfile = ConditionProfile.createBinaryProfile();
        @Child private JSToNumberNode toNumberNode;

        Uint32ArrayWriteElementCacheNode(JSContext context, boolean isStrict, ScriptArray arrayType, boolean writeOwn, ArrayWriteElementCacheNode arrayCacheNext) {
            super(context, isStrict, arrayType, writeOwn, arrayCacheNext);
        }

        @Override
        protected int toInt(Object value) {
            if (toIntProfile.profile(value instanceof Integer)) {
                return (int) value;
            } else {
                return (int) JSRuntime.toUInt32(toNumber(value));
            }
        }

        private Number toNumber(Object value) {
            if (toNumberNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                toNumberNode = insert(JSToNumberNode.create());
            }
            return toNumberNode.executeNumber(value);
        }
    }

    private static class TypedFloatArrayWriteElementCacheNode extends ArrayClassGuardCachedArrayWriteElementCacheNode {
        private final ConditionProfile inBoundsProfile = ConditionProfile.createBinaryProfile();
        @Child private JSToDoubleNode toDoubleNode;

        TypedFloatArrayWriteElementCacheNode(JSContext context, boolean isStrict, ScriptArray arrayType, boolean writeOwn, ArrayWriteElementCacheNode arrayCacheNext) {
            super(context, isStrict, arrayType, writeOwn, arrayCacheNext);
            this.toDoubleNode = JSToDoubleNode.create();
        }

        @Override
        protected boolean executeWithTargetAndArrayAndIndexAndValueUnguarded(DynamicObject target, ScriptArray array, long index, Object value, boolean arrayCondition) {
            TypedFloatArray<?> typedArray = (TypedFloatArray<?>) cast(array);
            double dValue = toDouble(value); // could throw
            checkDetachedArrayBuffer(target);
            if (inBoundsProfile.profile(typedArray.hasElement(target, index, arrayCondition))) {
                typedArray.setDouble(target, (int) index, dValue, arrayCondition);
            } else {
                // do nothing; cf. ES6 9.4.5.9 IntegerIndexedElementSet(O, index, value)
            }
            return true;
        }

        private double toDouble(Object value) {
            return toDoubleNode.executeDouble(value);
        }
    }

    private abstract static class ToPropertyKeyCachedWriteElementTypeCacheNode extends CachedWriteElementTypeCacheNode {
        @Child private JSToPropertyKeyNode indexToPropertyKeyNode;
        protected final JSClassProfile classProfile = JSClassProfile.create();

        ToPropertyKeyCachedWriteElementTypeCacheNode(JSContext context, boolean isStrict, boolean writeOwn) {
            super(context, isStrict, writeOwn);
        }

        protected final Object toPropertyKey(Object index) {
            if (indexToPropertyKeyNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                indexToPropertyKeyNode = insert(JSToPropertyKeyNode.create());
            }
            return indexToPropertyKeyNode.execute(index);
        }
    }

    private static class StringWriteElementTypeCacheNode extends ToPropertyKeyCachedWriteElementTypeCacheNode {
        private final Class<?> stringClass;
        private final BranchProfile intIndexBranch = BranchProfile.create();
        private final BranchProfile stringIndexBranch = BranchProfile.create();
        private final ConditionProfile isImmutable = ConditionProfile.createBinaryProfile();

        StringWriteElementTypeCacheNode(JSContext context, boolean isStrict, Class<?> stringClass, boolean writeOwn) {
            super(context, isStrict, writeOwn);
            this.stringClass = stringClass;
        }

        @Override
        protected void executeWithTargetAndIndexUnguarded(Object target, Object index, Object value, Object receiver) {
            CharSequence charSequence = (CharSequence) stringClass.cast(target);
            if (index instanceof Integer) {
                intIndexBranch.enter();
                int intIndex = (int) index;
                if (isImmutable.profile(intIndex >= 0 && intIndex < JSRuntime.length(charSequence))) {
                    // cannot set characters of immutable strings
                    if (isStrict) {
                        throw Errors.createTypeErrorNotWritableProperty(Boundaries.stringValueOf(index), charSequence, this);
                    }
                    return;
                }
            }
            stringIndexBranch.enter();
            JSObject.setWithReceiver(JSString.create(context, charSequence), toPropertyKey(index), value, target, isStrict, classProfile);
        }

        @Override
        protected void executeWithTargetAndIndexUnguarded(Object target, int index, Object value, Object receiver) {
            CharSequence charSequence = (CharSequence) stringClass.cast(target);
            if (isImmutable.profile(index >= 0 && index < JSRuntime.length(charSequence))) {
                // cannot set characters of immutable strings
                if (isStrict) {
                    throw Errors.createTypeErrorNotWritableProperty(Boundaries.stringValueOf(index), charSequence, this);
                }
                return;
            } else {
                JSObject.setWithReceiver(JSString.create(context, charSequence), index, value, target, isStrict, classProfile);
            }
        }

        @Override
        public boolean guard(Object target) {
            return stringClass.isInstance(target);
        }
    }

    private static class NumberWriteElementTypeCacheNode extends ToPropertyKeyCachedWriteElementTypeCacheNode {
        private final Class<?> numberClass;

        NumberWriteElementTypeCacheNode(JSContext context, boolean isStrict, Class<?> numberClass, boolean writeOwn) {
            super(context, isStrict, writeOwn);
            this.numberClass = numberClass;
        }

        @Override
        protected void executeWithTargetAndIndexUnguarded(Object target, Object index, Object value, Object receiver) {
            Number number = (Number) target;
            JSObject.setWithReceiver(JSNumber.create(context, number), toPropertyKey(index), value, target, isStrict, classProfile);
        }

        @Override
        protected void executeWithTargetAndIndexUnguarded(Object target, int index, Object value, Object receiver) {
            Number number = (Number) target;
            JSObject.setWithReceiver(JSNumber.create(context, number), index, value, target, isStrict, classProfile);
        }

        @Override
        public boolean guard(Object target) {
            return numberClass.isInstance(target);
        }
    }

    private static class BooleanWriteElementTypeCacheNode extends ToPropertyKeyCachedWriteElementTypeCacheNode {
        BooleanWriteElementTypeCacheNode(JSContext context, boolean isStrict, boolean writeOwn) {
            super(context, isStrict, writeOwn);
        }

        @Override
        protected void executeWithTargetAndIndexUnguarded(Object target, Object index, Object value, Object receiver) {
            Boolean bool = (Boolean) target;
            JSObject.setWithReceiver(JSBoolean.create(context, bool), toPropertyKey(index), value, target, isStrict, classProfile);
        }

        @Override
        protected void executeWithTargetAndIndexUnguarded(Object target, int index, Object value, Object receiver) {
            Boolean bool = (Boolean) target;
            JSObject.setWithReceiver(JSBoolean.create(context, bool), index, value, target, isStrict, classProfile);
        }

        @Override
        public boolean guard(Object target) {
            return target instanceof Boolean;
        }
    }

    private static class SymbolWriteElementTypeCacheNode extends ToPropertyKeyCachedWriteElementTypeCacheNode {
        SymbolWriteElementTypeCacheNode(JSContext context, boolean isStrict, boolean writeOwn) {
            super(context, isStrict, writeOwn);
        }

        @Override
        protected void executeWithTargetAndIndexUnguarded(Object target, Object index, Object value, Object receiver) {
            if (isStrict) {
                throw Errors.createTypeError("cannot set element on Symbol in strict mode", this);
            }
            Symbol symbol = (Symbol) target;
            JSObject.setWithReceiver(JSSymbol.create(context, symbol), toPropertyKey(index), value, receiver, isStrict, classProfile);
        }

        @Override
        protected void executeWithTargetAndIndexUnguarded(Object target, int index, Object value, Object receiver) {
            if (isStrict) {
                throw Errors.createTypeError("cannot set element on Symbol in strict mode", this);
            }
            Symbol symbol = (Symbol) target;
            JSObject.setWithReceiver(JSSymbol.create(context, symbol), index, value, receiver, isStrict, classProfile);
        }

        @Override
        public boolean guard(Object target) {
            return target instanceof Symbol;
        }
    }

    private static class BigIntWriteElementTypeCacheNode extends ToPropertyKeyCachedWriteElementTypeCacheNode {
        BigIntWriteElementTypeCacheNode(JSContext context, boolean isStrict, boolean writeOwn) {
            super(context, isStrict, writeOwn);
        }

        @Override
        protected void executeWithTargetAndIndexUnguarded(Object target, Object index, Object value, Object receiver) {
            BigInt bigInt = (BigInt) target;
            JSObject.setWithReceiver(JSBigInt.create(context, bigInt), toPropertyKey(index), value, target, isStrict, classProfile);
        }

        @Override
        protected void executeWithTargetAndIndexUnguarded(Object target, int index, Object value, Object receiver) {
            BigInt bigInt = (BigInt) target;
            JSObject.setWithReceiver(JSBigInt.create(context, bigInt), index, value, target, isStrict, classProfile);
        }

        @Override
        public boolean guard(Object target) {
            return target instanceof BigInt;
        }
    }

    static class TruffleObjectWriteElementTypeCacheNode extends CachedWriteElementTypeCacheNode {
        private final Class<? extends TruffleObject> targetClass;
        @Child private InteropLibrary interop;
        @Child private InteropLibrary keyInterop;
        @Child private InteropLibrary setterInterop;
        @Child private ExportValueNode exportKey;
        @Child private ExportValueNode exportValue;

        TruffleObjectWriteElementTypeCacheNode(JSContext context, boolean isStrict, Class<? extends TruffleObject> targetClass, boolean writeOwn) {
            super(context, isStrict, writeOwn);
            this.targetClass = targetClass;
            this.exportKey = ExportValueNode.create();
            this.exportValue = ExportValueNode.create();
            this.interop = InteropLibrary.getFactory().createDispatched(3);
            this.keyInterop = InteropLibrary.getFactory().createDispatched(3);
        }

        @Override
        protected void executeWithTargetAndIndexUnguarded(Object target, Object index, Object value, Object receiver) {
            TruffleObject truffleObject = targetClass.cast(target);
            if (interop.isNull(truffleObject)) {
                throw Errors.createTypeErrorCannotSetProperty(index, truffleObject, this);
            }
            Object convertedKey = exportKey.execute(index);
            if (convertedKey instanceof Symbol) {
                return;
            }
            Object exportedValue = exportValue.execute(value);
            if (keyInterop.isString(convertedKey)) {
                try {
                    interop.writeMember(truffleObject, keyInterop.asString(convertedKey), exportedValue);
                } catch (UnknownIdentifierException e) {
                    if (context.isOptionNashornCompatibilityMode() && convertedKey instanceof String) {
                        tryInvokeSetter(truffleObject, (String) convertedKey, exportedValue);
                    }
                    // do nothing
                } catch (UnsupportedTypeException | UnsupportedMessageException e) {
                    throw Errors.createTypeErrorInteropException(truffleObject, e, "writeMember", this);
                }
            } else if (keyInterop.fitsInLong(convertedKey)) {
                try {
                    interop.writeArrayElement(truffleObject, keyInterop.asLong(convertedKey), exportedValue);
                } catch (InvalidArrayIndexException e) {
                    // do nothing
                } catch (UnsupportedTypeException | UnsupportedMessageException e) {
                    throw Errors.createTypeErrorInteropException(truffleObject, e, "writeArrayElement", this);
                }
            } else {
                // do nothing
            }
        }

        @Override
        protected void executeWithTargetAndIndexUnguarded(Object target, int index, Object value, Object receiver) {
            executeWithTargetAndIndexUnguarded(target, (Object) index, value, receiver);
        }

        @Override
        public boolean guard(Object target) {
            return targetClass.isInstance(target) && !JSObject.isJSObject(target);
        }

        private void tryInvokeSetter(TruffleObject thisObj, String key, Object value) {
            assert context.isOptionNashornCompatibilityMode();
            TruffleLanguage.Env env = context.getRealm().getEnv();
            if (env.isHostObject(thisObj)) {
                String setterKey = PropertyCacheNode.getAccessorKey("set", key);
                if (setterKey == null) {
                    return;
                }
                if (setterInterop == null) {
                    CompilerDirectives.transferToInterpreterAndInvalidate();
                    setterInterop = insert(InteropLibrary.getFactory().createDispatched(3));
                }
                if (!setterInterop.isMemberInvocable(thisObj, setterKey)) {
                    return;
                }
                try {
                    setterInterop.invokeMember(thisObj, setterKey, value);
                } catch (UnknownIdentifierException | UnsupportedMessageException | UnsupportedTypeException | ArityException e) {
                    // silently ignore
                }
            }
        }
    }

    @Override
    public JavaScriptNode getTarget() {
        return targetNode;
    }

    public JavaScriptNode getElement() {
        return indexNode;
    }

    public JavaScriptNode getValue() {
        return valueNode;
    }

    public JSContext getContext() {
        return context;
    }

    public boolean isStrict() {
        return isStrict;
    }

    public boolean writeOwn() {
        return writeOwn;
    }

    @Override
    protected JavaScriptNode copyUninitialized() {
        return create(cloneUninitialized(targetNode), cloneUninitialized(indexNode), cloneUninitialized(valueNode), getContext(), isStrict(), writeOwn());
    }

    @Override
    public boolean isResultAlwaysOfType(Class<?> clazz) {
        return valueNode.isResultAlwaysOfType(clazz);
    }

    public WriteElementTypeCacheNode getTypeCacheNode() {
        if (typeCacheNode == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            this.typeCacheNode = insert(new UninitWriteElementTypeCacheNode(context, isStrict, writeOwn));
        }
        return typeCacheNode;
    }

    public static WriteElementNode createCachedInterop(ContextReference<JSRealm> contextRef) {
        return create(contextRef.get().getContext(), true);
    }
}
