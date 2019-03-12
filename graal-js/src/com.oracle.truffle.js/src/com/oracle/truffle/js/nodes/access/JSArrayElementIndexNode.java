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

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.dsl.ReportPolymorphism;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.object.DynamicObject;
import com.oracle.truffle.js.nodes.JavaScriptBaseNode;
import com.oracle.truffle.js.runtime.JSContext;
import com.oracle.truffle.js.runtime.JSTruffleOptions;
import com.oracle.truffle.js.runtime.array.ScriptArray;
import com.oracle.truffle.js.runtime.builtins.JSArgumentsObject;
import com.oracle.truffle.js.runtime.builtins.JSArray;
import com.oracle.truffle.js.runtime.builtins.JSArrayBufferView;
import com.oracle.truffle.js.runtime.builtins.JSProxy;
import com.oracle.truffle.js.runtime.objects.JSObject;
import com.oracle.truffle.js.runtime.objects.Null;

@ReportPolymorphism
public abstract class JSArrayElementIndexNode extends JavaScriptBaseNode {
    protected static final int MAX_CACHED_ARRAY_TYPES = 4;
    protected final JSContext context;
    @Child private IsArrayNode isArrayNode;

    protected JSArrayElementIndexNode(JSContext context) {
        this.context = context;
    }

    protected static boolean hasHoles(DynamicObject object, boolean isArray) {
        return JSObject.getArray(object).hasHoles(object, isArray);
    }

    protected static ScriptArray getArrayType(DynamicObject object, boolean arrayCondition) {
        return JSObject.getArray(object, arrayCondition);
    }

    /**
     * Workaround for GR-830: Cached values are initialized before guards are evaluated.
     */
    protected static ScriptArray getArrayTypeIfArray(DynamicObject object, boolean arrayCondition) {
        if (!arrayCondition) {
            return null;
        }
        return getArrayType(object, arrayCondition);
    }

    protected final boolean isArraySuitableForEnumBasedProcessing(TruffleObject object, long length) {
        return length > JSTruffleOptions.BigArrayThreshold && !JSArrayBufferView.isJSArrayBufferView(object) && !JSProxy.isProxy(object) &&
                        (context.getArrayPrototypeNoElementsAssumption().isValid() || !JSObject.isJSObject(object) || JSObject.getPrototype((DynamicObject) object) == Null.instance);
    }

    /**
     * @param object dummy parameter to force evaluation of the guard by the DSL
     */
    protected final boolean hasPrototypeElements(DynamicObject object) {
        return !context.getArrayPrototypeNoElementsAssumption().isValid();
    }

    protected final boolean isArray(TruffleObject obj) {
        if (isArrayNode == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            isArrayNode = insert(IsArrayNode.createIsFastOrTypedArray());
        }
        return isArrayNode.execute(obj);
    }

    protected static boolean isSupportedArray(DynamicObject object) {
        return JSArray.isJSFastArray(object) || JSArgumentsObject.isJSFastArgumentsObject(object) || JSArrayBufferView.isJSArrayBufferView(object);
    }
}