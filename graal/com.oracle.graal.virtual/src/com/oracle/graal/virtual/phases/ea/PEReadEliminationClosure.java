/*
 * Copyright (c) 2011, 2016, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.graal.virtual.phases.ea;

import static com.oracle.graal.nodes.NamedLocationIdentity.ARRAY_LENGTH_LOCATION;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import com.oracle.graal.compiler.common.LocationIdentity;
import com.oracle.graal.compiler.common.cfg.Loop;
import com.oracle.graal.compiler.common.spi.ConstantFieldProvider;
import com.oracle.graal.graph.Node;
import com.oracle.graal.nodes.FieldLocationIdentity;
import com.oracle.graal.nodes.FixedNode;
import com.oracle.graal.nodes.FixedWithNextNode;
import com.oracle.graal.nodes.LoopBeginNode;
import com.oracle.graal.nodes.LoopExitNode;
import com.oracle.graal.nodes.NamedLocationIdentity;
import com.oracle.graal.nodes.PhiNode;
import com.oracle.graal.nodes.ProxyNode;
import com.oracle.graal.nodes.StructuredGraph.ScheduleResult;
import com.oracle.graal.nodes.ValueNode;
import com.oracle.graal.nodes.ValueProxyNode;
import com.oracle.graal.nodes.cfg.Block;
import com.oracle.graal.nodes.extended.UnboxNode;
import com.oracle.graal.nodes.extended.UnsafeLoadNode;
import com.oracle.graal.nodes.extended.UnsafeStoreNode;
import com.oracle.graal.nodes.java.ArrayLengthNode;
import com.oracle.graal.nodes.java.LoadFieldNode;
import com.oracle.graal.nodes.java.LoadIndexedNode;
import com.oracle.graal.nodes.java.StoreFieldNode;
import com.oracle.graal.nodes.java.StoreIndexedNode;
import com.oracle.graal.nodes.memory.MemoryCheckpoint;
import com.oracle.graal.nodes.spi.LoweringProvider;
import com.oracle.graal.nodes.type.StampTool;
import com.oracle.graal.nodes.util.GraphUtil;
import com.oracle.graal.nodes.virtual.VirtualArrayNode;
import com.oracle.graal.virtual.phases.ea.PEReadEliminationBlockState.ReadCacheEntry;

import jdk.vm.ci.meta.ConstantReflectionProvider;
import jdk.vm.ci.meta.JavaConstant;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.MetaAccessProvider;
import jdk.vm.ci.meta.ResolvedJavaType;

public class PEReadEliminationClosure extends PartialEscapeClosure<PEReadEliminationBlockState> {

    private static final EnumMap<JavaKind, LocationIdentity> UNBOX_LOCATIONS;

    static {
        UNBOX_LOCATIONS = new EnumMap<>(JavaKind.class);
        for (JavaKind kind : JavaKind.values()) {
            UNBOX_LOCATIONS.put(kind, NamedLocationIdentity.immutable("PEA unbox " + kind.getJavaName()));
        }
    }

    public PEReadEliminationClosure(ScheduleResult schedule, MetaAccessProvider metaAccess, ConstantReflectionProvider constantReflection, ConstantFieldProvider constantFieldProvider,
                    LoweringProvider loweringProvider) {
        super(schedule, metaAccess, constantReflection, constantFieldProvider, loweringProvider);
    }

    @Override
    protected PEReadEliminationBlockState getInitialState() {
        return new PEReadEliminationBlockState();
    }

    @Override
    protected boolean processNode(Node node, PEReadEliminationBlockState state, GraphEffectList effects, FixedWithNextNode lastFixedNode) {
        if (super.processNode(node, state, effects, lastFixedNode)) {
            return true;
        }

        if (node instanceof LoadFieldNode) {
            return processLoadField((LoadFieldNode) node, state, effects);
        } else if (node instanceof StoreFieldNode) {
            return processStoreField((StoreFieldNode) node, state, effects);
        } else if (node instanceof LoadIndexedNode) {
            return processLoadIndexed((LoadIndexedNode) node, state, effects);
        } else if (node instanceof StoreIndexedNode) {
            return processStoreIndexed((StoreIndexedNode) node, state, effects);
        } else if (node instanceof ArrayLengthNode) {
            return processArrayLength((ArrayLengthNode) node, state, effects);
        } else if (node instanceof UnboxNode) {
            return processUnbox((UnboxNode) node, state, effects);
        } else if (node instanceof UnsafeLoadNode) {
            return processUnsafeLoad((UnsafeLoadNode) node, state, effects);
        } else if (node instanceof UnsafeStoreNode) {
            return processUnsafeStore((UnsafeStoreNode) node, state, effects);
        } else if (node instanceof MemoryCheckpoint.Single) {
            COUNTER_MEMORYCHECKPOINT.increment();
            LocationIdentity identity = ((MemoryCheckpoint.Single) node).getLocationIdentity();
            processIdentity(state, identity);
        } else if (node instanceof MemoryCheckpoint.Multi) {
            COUNTER_MEMORYCHECKPOINT.increment();
            for (LocationIdentity identity : ((MemoryCheckpoint.Multi) node).getLocationIdentities()) {
                processIdentity(state, identity);
            }
        }

        return false;
    }

    private boolean processStore(FixedNode store, ValueNode object, LocationIdentity identity, int index, ValueNode value, PEReadEliminationBlockState state, GraphEffectList effects) {
        ValueNode unproxiedObject = GraphUtil.unproxify(object);
        ValueNode cachedValue = state.getReadCache(object, identity, index, this);

        ValueNode finalValue = getScalarAlias(value);
        boolean result = false;
        if (GraphUtil.unproxify(finalValue) == GraphUtil.unproxify(cachedValue)) {
            effects.deleteNode(store);
            result = true;
        }
        state.killReadCache(identity, index);
        state.addReadCache(unproxiedObject, identity, index, finalValue, this);
        return result;
    }

    private boolean processLoad(FixedNode load, ValueNode object, LocationIdentity identity, int index, PEReadEliminationBlockState state, GraphEffectList effects) {
        ValueNode unproxiedObject = GraphUtil.unproxify(object);
        ValueNode cachedValue = state.getReadCache(unproxiedObject, identity, index, this);
        if (cachedValue != null) {
            effects.replaceAtUsages(load, cachedValue);
            addScalarAlias(load, cachedValue);
            return true;
        } else {
            state.addReadCache(unproxiedObject, identity, index, load, this);
            return false;
        }
    }

    private boolean processUnsafeLoad(UnsafeLoadNode load, PEReadEliminationBlockState state, GraphEffectList effects) {
        if (load.offset().isConstant()) {
            ResolvedJavaType type = StampTool.typeOrNull(load.object());
            if (type != null && type.isArray()) {
                long offset = load.offset().asJavaConstant().asLong();
                int index = VirtualArrayNode.entryIndexForOffset(offset, load.accessKind(), type.getComponentType(), Integer.MAX_VALUE);
                ValueNode object = GraphUtil.unproxify(load.object());
                LocationIdentity location = NamedLocationIdentity.getArrayLocation(type.getComponentType().getJavaKind());
                ValueNode cachedValue = state.getReadCache(object, location, index, this);
                if (cachedValue != null && load.stamp().isCompatible(cachedValue.stamp())) {
                    effects.replaceAtUsages(load, cachedValue);
                    addScalarAlias(load, cachedValue);
                    return true;
                } else {
                    state.addReadCache(object, location, index, load, this);
                }
            }
        }
        return false;
    }

    private boolean processUnsafeStore(UnsafeStoreNode store, PEReadEliminationBlockState state, GraphEffectList effects) {
        ResolvedJavaType type = StampTool.typeOrNull(store.object());
        if (type != null && type.isArray()) {
            LocationIdentity location = NamedLocationIdentity.getArrayLocation(type.getComponentType().getJavaKind());
            if (store.offset().isConstant()) {
                long offset = store.offset().asJavaConstant().asLong();
                int index = VirtualArrayNode.entryIndexForOffset(offset, store.accessKind(), type.getComponentType(), Integer.MAX_VALUE);
                return processStore(store, store.object(), location, index, store.value(), state, effects);
            } else {
                processIdentity(state, location);
            }
        } else {
            state.killReadCache();
        }
        return false;
    }

    private boolean processArrayLength(ArrayLengthNode length, PEReadEliminationBlockState state, GraphEffectList effects) {
        return processLoad(length, length.array(), ARRAY_LENGTH_LOCATION, -1, state, effects);
    }

    private boolean processStoreField(StoreFieldNode store, PEReadEliminationBlockState state, GraphEffectList effects) {
        if (store.isVolatile()) {
            state.killReadCache();
            return false;
        }
        return processStore(store, store.object(), new FieldLocationIdentity(store.field()), -1, store.value(), state, effects);
    }

    private boolean processLoadField(LoadFieldNode load, PEReadEliminationBlockState state, GraphEffectList effects) {
        if (load.isVolatile()) {
            state.killReadCache();
            return false;
        }
        return processLoad(load, load.object(), new FieldLocationIdentity(load.field()), -1, state, effects);
    }

    private boolean processStoreIndexed(StoreIndexedNode store, PEReadEliminationBlockState state, GraphEffectList effects) {
        LocationIdentity arrayLocation = NamedLocationIdentity.getArrayLocation(store.elementKind());
        if (store.index().isConstant()) {
            int index = ((JavaConstant) store.index().asConstant()).asInt();
            return processStore(store, store.array(), arrayLocation, index, store.value(), state, effects);
        } else {
            state.killReadCache(arrayLocation, -1);
        }
        return false;
    }

    private boolean processLoadIndexed(LoadIndexedNode load, PEReadEliminationBlockState state, GraphEffectList effects) {
        if (load.index().isConstant()) {
            int index = ((JavaConstant) load.index().asConstant()).asInt();
            LocationIdentity arrayLocation = NamedLocationIdentity.getArrayLocation(load.elementKind());
            return processLoad(load, load.array(), arrayLocation, index, state, effects);
        }
        return false;
    }

    private boolean processUnbox(UnboxNode unbox, PEReadEliminationBlockState state, GraphEffectList effects) {
        return processLoad(unbox, unbox.getValue(), UNBOX_LOCATIONS.get(unbox.getBoxingKind()), -1, state, effects);
    }

    private static void processIdentity(PEReadEliminationBlockState state, LocationIdentity identity) {
        if (identity.isAny()) {
            state.killReadCache();
        } else {
            state.killReadCache(identity, -1);
        }
    }

    @Override
    protected void processInitialLoopState(Loop<Block> loop, PEReadEliminationBlockState initialState) {
        super.processInitialLoopState(loop, initialState);

        for (PhiNode phi : ((LoopBeginNode) loop.getHeader().getBeginNode()).phis()) {
            ValueNode firstValue = phi.valueAt(0);
            if (firstValue != null) {
                firstValue = GraphUtil.unproxify(firstValue);
                for (Map.Entry<ReadCacheEntry, ValueNode> entry : new ArrayList<>(initialState.getReadCache().entrySet())) {
                    if (entry.getKey().object == firstValue) {
                        initialState.addReadCache(phi, entry.getKey().identity, entry.getKey().index, entry.getValue(), this);
                    }
                }
            }
        }
    }

    @Override
    protected void processLoopExit(LoopExitNode exitNode, PEReadEliminationBlockState initialState, PEReadEliminationBlockState exitState, GraphEffectList effects) {
        super.processLoopExit(exitNode, initialState, exitState, effects);

        if (exitNode.graph().hasValueProxies()) {
            for (Map.Entry<ReadCacheEntry, ValueNode> entry : exitState.getReadCache().entrySet()) {
                if (initialState.getReadCache().get(entry.getKey()) != entry.getValue()) {
                    ValueNode value = exitState.getReadCache(entry.getKey().object, entry.getKey().identity, entry.getKey().index, this);
                    assert value != null : "Got null from read cache, entry's value:" + entry.getValue();
                    if (!(value instanceof ProxyNode) || ((ProxyNode) value).proxyPoint() != exitNode) {
                        ProxyNode proxy = new ValueProxyNode(value, exitNode);
                        effects.addFloatingNode(proxy, "readCacheProxy");
                        entry.setValue(proxy);
                    }
                }
            }
        }
    }

    @Override
    protected PEReadEliminationBlockState cloneState(PEReadEliminationBlockState other) {
        return new PEReadEliminationBlockState(other);
    }

    @Override
    protected MergeProcessor createMergeProcessor(Block merge) {
        return new ReadEliminationMergeProcessor(merge);
    }

    private class ReadEliminationMergeProcessor extends MergeProcessor {

        ReadEliminationMergeProcessor(Block mergeBlock) {
            super(mergeBlock);
        }

        @Override
        protected void merge(List<PEReadEliminationBlockState> states) {
            super.merge(states);

            mergeReadCache(states);
        }

        private void mergeReadCache(List<PEReadEliminationBlockState> states) {
            for (Map.Entry<ReadCacheEntry, ValueNode> entry : states.get(0).readCache.entrySet()) {
                ReadCacheEntry key = entry.getKey();
                ValueNode value = entry.getValue();
                boolean phi = false;
                for (int i = 1; i < states.size(); i++) {
                    ValueNode otherValue = states.get(i).readCache.get(key);
                    // e.g. unsafe loads / stores with different access kinds have different stamps
                    // although location, object and offset are the same, in this case we cannot
                    // create a phi nor can we set a common value
                    if (otherValue == null || !value.stamp().isCompatible(otherValue.stamp())) {
                        value = null;
                        phi = false;
                        break;
                    }
                    if (!phi && otherValue != value) {
                        phi = true;
                    }
                }
                if (phi) {
                    PhiNode phiNode = getPhi(entry, value.stamp().unrestricted());
                    mergeEffects.addFloatingNode(phiNode, "mergeReadCache");
                    for (int i = 0; i < states.size(); i++) {
                        ValueNode v = states.get(i).getReadCache(key.object, key.identity, key.index, PEReadEliminationClosure.this);
                        assert phiNode.stamp().isCompatible(v.stamp()) : "Cannot create read elimination phi for inputs with incompatible stamps.";
                        setPhiInput(phiNode, i, v);
                    }
                    newState.readCache.put(key, phiNode);
                } else if (value != null) {
                    newState.readCache.put(key, value);
                }
            }
            for (PhiNode phi : getPhis()) {
                if (phi.getStackKind() == JavaKind.Object) {
                    for (Map.Entry<ReadCacheEntry, ValueNode> entry : states.get(0).readCache.entrySet()) {
                        if (entry.getKey().object == getPhiValueAt(phi, 0)) {
                            mergeReadCachePhi(phi, entry.getKey().identity, entry.getKey().index, states);
                        }
                    }
                }
            }
        }

        private void mergeReadCachePhi(PhiNode phi, LocationIdentity identity, int index, List<PEReadEliminationBlockState> states) {
            ValueNode[] values = new ValueNode[states.size()];
            values[0] = states.get(0).getReadCache(getPhiValueAt(phi, 0), identity, index, PEReadEliminationClosure.this);
            if (values[0] != null) {
                for (int i = 1; i < states.size(); i++) {
                    ValueNode value = states.get(i).getReadCache(getPhiValueAt(phi, i), identity, index, PEReadEliminationClosure.this);
                    // e.g. unsafe loads / stores with same identity and different access kinds see
                    // mergeReadCache(states)
                    if (value == null || !values[i - 1].stamp().isCompatible(value.stamp())) {
                        return;
                    }
                    values[i] = value;
                }

                PhiNode phiNode = getPhi(new ReadCacheEntry(identity, phi, index), values[0].stamp().unrestricted());
                mergeEffects.addFloatingNode(phiNode, "mergeReadCachePhi");
                for (int i = 0; i < values.length; i++) {
                    setPhiInput(phiNode, i, values[i]);
                }
                newState.readCache.put(new ReadCacheEntry(identity, phi, index), phiNode);
            }
        }
    }
}
