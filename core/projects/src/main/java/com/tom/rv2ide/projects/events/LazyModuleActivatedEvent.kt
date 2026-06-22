package com.tom.rv2ide.projects.events

import com.tom.rv2ide.projects.ModuleProject

/** Fired after a lazily registered module completes deferred activation/indexing. */
class LazyModuleActivatedEvent(val module: ModuleProject)
