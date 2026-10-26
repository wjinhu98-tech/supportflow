package dev.supportflow.assistant.runtime.store;

/** A server-defined read-only workflow, not arbitrary model-selected code. */
public enum AgentPhase { PLAN, ORDER_LOOKUP, LOGISTICS_LOOKUP, COMPLETE }
