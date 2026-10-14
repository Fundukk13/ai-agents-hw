package edu.hse.agent.runtime

class SimulatedCrash : RuntimeException("Injected crash after tool execution, before saving its result")
