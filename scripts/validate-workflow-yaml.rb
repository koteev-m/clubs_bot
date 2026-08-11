#!/usr/bin/env ruby

require "open3"
require "pathname"
require "psych"

module WorkflowYamlSafety
  class ModelError < StandardError; end

  module_function

  WORKFLOW_PATH = %r{\A\.github/workflows/[^/]+\.(?:yml|yaml)\z}
  STRING_TAG = "tag:yaml.org,2002:str"
  SAFE_EXPLICIT_TAGS = {
    "tag:yaml.org,2002:null" => true,
    "tag:yaml.org,2002:bool" => true,
    "tag:yaml.org,2002:int" => true,
    "tag:yaml.org,2002:float" => true,
    "tag:yaml.org,2002:str" => true,
    "tag:yaml.org,2002:seq" => true,
    "tag:yaml.org,2002:map" => true,
  }.freeze
  SAFE_SCALAR_CLASSES = [NilClass, TrueClass, FalseClass, Integer, Float, String].freeze

  UNVISITED = 0
  VISITING = 1
  VISITED = 2

  # Current tracked maximums are 9 anchors, 9 alias references, AST depth 7,
  # 304 AST nodes, and 10,554 bytes. These limits leave substantial growth room while keeping
  # parsing, alias dependency analysis, merge expansion, and loaded graphs bounded.
  MAX_ANCHORS = 128
  MAX_ALIAS_EDGES = 256
  MAX_ALIAS_DEPENDENCY_EDGES = 1_024
  MAX_ALIAS_GRAPH_DEPTH = 32
  MAX_YAML_BYTES = 4_000_000
  MAX_AST_DEPTH = 128
  MAX_AST_NODES = 50_000
  MAX_MERGE_EXPANSION_STEPS = 50_000
  MAX_EFFECTIVE_MAPPING_KEYS = 50_000
  MAX_LOADED_CONTAINER_DEPTH = 128
  MAX_LOADED_CONTAINER_NODES = 50_000
  MAX_ANCHOR_IDENTITY_BYTES = 256
  ANCHOR_IDENTITY = /\A[^\x00-\x20\x7f,\[\]\{\}]+\z/u

  def alias_diagnostic(relative_path, document_index, category, kind, name, detail)
    "#{relative_path}: document #{document_index}: #{category}: " \
      "YAML #{kind} #{name.inspect} #{detail}"
  end

  def node_line(node)
    return "unknown" unless node.respond_to?(:start_line) && node.start_line
    (node.start_line + 1).to_s
  end

  def validate_identity!(identity, relative_path, document_index, kind, node)
    valid = identity.is_a?(String) && !identity.empty? &&
      identity.bytesize <= MAX_ANCHOR_IDENTITY_BYTES
    if valid
      utf8 = identity.dup.force_encoding(Encoding::UTF_8)
      valid = utf8.valid_encoding? && ANCHOR_IDENTITY.match?(utf8)
    end
    return identity if valid

    raise ModelError, alias_diagnostic(
      relative_path,
      document_index,
      "malformed",
      kind,
      identity,
      "has an invalid identity at line #{node_line(node)}"
    )
  end

  def canonical_scalar_key(node, relative_path, document_index)
    unless node.is_a?(Psych::Nodes::Scalar)
      raise ModelError,
            "#{relative_path}: document #{document_index}: malformed non-scalar mapping key"
    end

    value = if node.tag == STRING_TAG || node.quoted || !node.plain
              node.value
            else
              loader = Psych::ClassLoader::Restricted.new([], [])
              Psych::ScalarScanner.new(loader).tokenize(node.value)
            end
    [value.class.name, value]
  rescue Psych::Exception, ArgumentError => error
    raise ModelError,
          "#{relative_path}: document #{document_index}: malformed mapping key " \
          "#{node.value.inspect} (#{error.class})"
  end

  def add_unique_key!(seen, canonical, display, relative_path, document_index)
    if seen.key?(canonical)
      raise ModelError,
            "#{relative_path}: document #{document_index}: duplicate mapping key " \
            "#{display.inspect}"
    end
    seen[canonical] = display
    if seen.length > MAX_EFFECTIVE_MAPPING_KEYS
      raise ModelError,
            "#{relative_path}: document #{document_index}: depth: effective mapping key " \
            "count exceeds #{MAX_EFFECTIVE_MAPPING_KEYS}"
    end
  end

  def inspect_document(document, relative_path, document_index)
    anchors = {}
    aliases = []
    mappings = []
    dependency_graph = Hash.new { |hash, key| hash[key] = {} }
    dependency_edges = 0
    node_count = 0
    order = 0
    stack = [[document, 0, []]]

    until stack.empty?
      node, depth, containing_anchors = stack.pop
      node_count += 1
      order += 1
      if node_count > MAX_AST_NODES
        raise ModelError,
              alias_diagnostic(
                relative_path,
                document_index,
                "depth",
                "alias",
                "<document>",
                "AST node count exceeds #{MAX_AST_NODES}"
              )
      end
      if depth > MAX_AST_DEPTH
        raise ModelError,
              alias_diagnostic(
                relative_path,
                document_index,
                "depth",
                "alias",
                "<document>",
                "AST depth exceeds #{MAX_AST_DEPTH}"
              )
      end

      tag = node.respond_to?(:tag) ? node.tag : nil
      if tag && !SAFE_EXPLICIT_TAGS.key?(tag)
        raise ModelError,
              "#{relative_path}: document #{document_index}: malformed: " \
              "custom YAML tag #{tag.inspect} is forbidden at line #{node_line(node)}"
      end

      child_anchors = containing_anchors
      if node.is_a?(Psych::Nodes::Alias)
        name = validate_identity!(
          node.anchor,
          relative_path,
          document_index,
          "alias",
          node
        )
        aliases << {name: name, order: order, node: node}
        if aliases.length > MAX_ALIAS_EDGES
          raise ModelError,
                alias_diagnostic(
                  relative_path,
                  document_index,
                  "depth",
                  "alias",
                  name,
                  "edge count exceeds #{MAX_ALIAS_EDGES}"
                )
        end
        containing_anchors.each do |source|
          next if dependency_graph[source].key?(name)
          dependency_graph[source][name] = true
          dependency_edges += 1
          if dependency_edges > MAX_ALIAS_DEPENDENCY_EDGES
            raise ModelError,
                  alias_diagnostic(
                    relative_path,
                    document_index,
                    "depth",
                    "alias",
                    name,
                    "dependency edge count exceeds #{MAX_ALIAS_DEPENDENCY_EDGES}"
                  )
          end
        end
      else
        anchor = node.respond_to?(:anchor) ? node.anchor : nil
        if anchor
          name = validate_identity!(
            anchor,
            relative_path,
            document_index,
            "anchor",
            node
          )
          if anchors.key?(name)
            raise ModelError,
                  alias_diagnostic(
                    relative_path,
                    document_index,
                    "duplicate",
                    "anchor",
                    name,
                    "is defined more than once"
                  )
          end
          anchors[name] = {node: node, order: order}
          if anchors.length > MAX_ANCHORS
            raise ModelError,
                  alias_diagnostic(
                    relative_path,
                    document_index,
                    "depth",
                    "anchor",
                    name,
                    "count exceeds #{MAX_ANCHORS}"
                  )
          end
          child_anchors = containing_anchors + [name]
        end
        mappings << node if node.is_a?(Psych::Nodes::Mapping)
      end

      children = node.respond_to?(:children) ? node.children : nil
      children&.reverse_each do |child|
        stack << [child, depth + 1, child_anchors]
      end
    end

    aliases.each do |reference|
      next if anchors.key?(reference[:name])
      raise ModelError,
            alias_diagnostic(
              relative_path,
              document_index,
              "unknown",
              "alias",
              reference[:name],
              "has no anchor definition"
            )
    end

    validate_alias_graph!(
      anchors,
      aliases,
      dependency_graph,
      relative_path,
      document_index
    )
    validate_mapping_semantics!(
      mappings,
      anchors,
      relative_path,
      document_index
    )
  end

  def validate_alias_graph!(anchors, aliases, graph, relative_path, document_index)
    states = Hash.new(UNVISITED)
    anchors.each_key do |start|
      next unless states[start] == UNVISITED
      states[start] = VISITING
      stack = [[start, 0]]

      until stack.empty?
        name, next_index = stack[-1]
        neighbors = graph[name].keys
        if next_index >= neighbors.length
          states[name] = VISITED
          stack.pop
          next
        end

        target = neighbors[next_index]
        stack[-1][1] += 1
        if states[target] == VISITING
          raise ModelError,
                alias_diagnostic(
                  relative_path,
                  document_index,
                  "cycle",
                  "alias",
                  target,
                  "closes a recursive anchor dependency"
                )
        end
        next if states[target] == VISITED

        states[target] = VISITING
        stack << [target, 0]
      end
    end

    aliases.each do |reference|
      definition = anchors.fetch(reference[:name])
      next if definition[:order] < reference[:order]
      raise ModelError,
            alias_diagnostic(
              relative_path,
              document_index,
              "forward",
              "alias",
              reference[:name],
              "appears before its anchor definition"
            )
    end

    depths = {}
    anchors.each_key do |start|
      next if depths.key?(start)
      stack = [[start, false]]
      until stack.empty?
        name, exiting = stack.pop
        if exiting
          depth = graph[name].keys.map { |target| depths.fetch(target) + 1 }.max || 0
          if depth > MAX_ALIAS_GRAPH_DEPTH
            raise ModelError,
                  alias_diagnostic(
                    relative_path,
                    document_index,
                    "depth",
                    "alias",
                    name,
                    "graph depth exceeds #{MAX_ALIAS_GRAPH_DEPTH}"
                  )
          end
          depths[name] = depth
          next
        end
        next if depths.key?(name)
        stack << [name, true]
        graph[name].keys.reverse_each do |target|
          stack << [target, false] unless depths.key?(target)
        end
      end
    end
  end

  def merge_source_mappings(value_node, anchors, relative_path, document_index)
    sources = []
    steps = 0
    stack = [value_node]
    until stack.empty?
      node = stack.pop
      steps += 1
      if steps > MAX_MERGE_EXPANSION_STEPS
        raise ModelError,
              "#{relative_path}: document #{document_index}: depth: YAML merge expansion " \
              "exceeds #{MAX_MERGE_EXPANSION_STEPS} steps"
      end
      case node
      when Psych::Nodes::Alias
        target = anchors.fetch(node.anchor)[:node]
        stack << target
      when Psych::Nodes::Mapping
        sources << node
      when Psych::Nodes::Sequence
        node.children.reverse_each { |child| stack << child }
      else
        raise ModelError,
              "#{relative_path}: document #{document_index}: YAML merge value must " \
              "resolve to a mapping"
      end
    end
    sources
  end

  def validate_mapping_semantics!(mappings, anchors, relative_path, document_index)
    models = {}
    mappings.each do |mapping|
      unless mapping.children.length.even?
        raise ModelError,
              "#{relative_path}: document #{document_index}: malformed mapping node"
      end
      syntactic = {}
      operations = []
      mapping.children.each_slice(2) do |key_node, value_node|
        canonical = canonical_scalar_key(key_node, relative_path, document_index)
        display = key_node.value
        add_unique_key!(
          syntactic,
          canonical,
          display,
          relative_path,
          document_index
        )
        if canonical == [String.name, "<<"]
          operations << [
            :merge,
            merge_source_mappings(
              value_node,
              anchors,
              relative_path,
              document_index
            ),
          ]
        else
          operations << [:key, canonical, display]
        end
      end
      dependencies = operations.each_with_object([]) do |operation, list|
        next unless operation[0] == :merge
        operation[1].each { |source| list << source.object_id }
      end
      models[mapping.object_id] = {
        node: mapping,
        operations: operations,
        dependencies: dependencies,
      }
    end

    states = Hash.new(UNVISITED)
    effective = {}
    mappings.each do |mapping|
      start_id = mapping.object_id
      next unless states[start_id] == UNVISITED
      states[start_id] = VISITING
      stack = [[start_id, 0]]

      until stack.empty?
        mapping_id, next_index = stack[-1]
        model = models.fetch(mapping_id)
        if next_index >= model[:dependencies].length
          seen = {}
          model[:operations].each do |operation|
            if operation[0] == :key
              add_unique_key!(
                seen,
                operation[1],
                operation[2],
                relative_path,
                document_index
              )
            else
              operation[1].each do |source|
                effective.fetch(source.object_id).each do |canonical, display|
                  add_unique_key!(
                    seen,
                    canonical,
                    display,
                    relative_path,
                    document_index
                  )
                end
              end
            end
          end
          effective[mapping_id] = seen
          states[mapping_id] = VISITED
          stack.pop
          next
        end

        dependency_id = model[:dependencies][next_index]
        stack[-1][1] += 1
        if states[dependency_id] == VISITING
          raise ModelError,
                alias_diagnostic(
                  relative_path,
                  document_index,
                  "cycle",
                  "alias",
                  "<merge>",
                  "closes a recursive merge dependency"
                )
        end
        next if states[dependency_id] == VISITED
        states[dependency_id] = VISITING
        stack << [dependency_id, 0]
      end
    end
  end

  def parse_and_validate(raw, relative_path)
    if raw.bytesize > MAX_YAML_BYTES
      raise ModelError,
            "#{relative_path}: depth: YAML byte size exceeds #{MAX_YAML_BYTES}"
    end
    stream = Psych.parse_stream(raw, filename: relative_path)
    documents = stream.children
    if documents.empty?
      raise ModelError, "#{relative_path}: malformed: expected exactly one YAML document, got 0"
    end
    documents.each_with_index do |document, index|
      inspect_document(document, relative_path, index + 1)
    end
    unless documents.length == 1
      raise ModelError,
            "#{relative_path}: malformed: expected exactly one YAML document, " \
            "got #{documents.length}"
    end
    stream
  end

  def validate_loaded_graph!(root, relative_path)
    states = Hash.new(UNVISITED)
    container_count = 0
    stack = [[:enter, root, 0]]

    until stack.empty?
      action, value, depth = stack.pop
      if action == :exit
        states[value.object_id] = VISITED
        next
      end

      if value.is_a?(Hash) || value.is_a?(Array)
        object_id = value.object_id
        if states[object_id] == VISITING
          raise ModelError,
                "#{relative_path}: cycle: safe_load produced a recursive container"
        end
        next if states[object_id] == VISITED
        if depth > MAX_LOADED_CONTAINER_DEPTH
          raise ModelError,
                "#{relative_path}: depth: loaded container depth exceeds " \
                "#{MAX_LOADED_CONTAINER_DEPTH}"
        end
        container_count += 1
        if container_count > MAX_LOADED_CONTAINER_NODES
          raise ModelError,
                "#{relative_path}: depth: loaded container count exceeds " \
                "#{MAX_LOADED_CONTAINER_NODES}"
        end
        states[object_id] = VISITING
        stack << [:exit, value, depth]
        children = value.is_a?(Hash) ? value.to_a.flatten(1) : value
        children.reverse_each { |child| stack << [:enter, child, depth + 1] }
      elsif !SAFE_SCALAR_CLASSES.any? { |klass| value.is_a?(klass) }
        raise ModelError,
              "#{relative_path}: malformed: safe_load produced forbidden class " \
              "#{value.class}"
      end
    end
  end

  def safe_load_workflow(raw, relative_path)
    parse_and_validate(raw, relative_path)
    data = begin
      Psych.safe_load(
        raw,
        permitted_classes: [],
        permitted_symbols: [],
        aliases: true,
        filename: relative_path
      )
    rescue Psych::Exception, ArgumentError => error
      raise ModelError,
            "#{relative_path}: malformed: safe_load rejected YAML (#{error.class})"
    rescue SystemStackError
      raise ModelError,
            "#{relative_path}: depth: safe_load exceeded the bounded YAML policy"
    end
    validate_loaded_graph!(data, relative_path)
    unless data.is_a?(Hash)
      raise ModelError, "#{relative_path}: workflow root is not a mapping"
    end
    data
  end

  def tracked_workflows(repository_root)
    inventory, _git_error, git_status = Open3.capture3(
      "git",
      "-C",
      repository_root.to_s,
      "ls-files",
      "-z",
      "--",
      ".github/workflows"
    )
    unless git_status.success?
      raise ModelError, "workflow-yaml: unable to inventory tracked workflow files"
    end

    workflows = inventory.split("\0").select { |path| WORKFLOW_PATH.match?(path) }.sort
    if workflows.empty?
      raise ModelError, "workflow-yaml: no tracked workflow files found"
    end
    workflows
  end

  def run(repository_root)
    unless repository_root.directory?
      warn "workflow-yaml: repository root is not a directory"
      return 1
    end

    begin
      workflows = tracked_workflows(repository_root)
    rescue ModelError => error
      warn error.message
      return 1
    end

    errors = []
    workflows.each do |relative_path|
      absolute_path = repository_root.join(relative_path)
      begin
        file_status = File.lstat(absolute_path)
        unless file_status.file? && !file_status.symlink?
          errors << "workflow-yaml: #{relative_path}: tracked workflow is not a regular file"
          next
        end
        safe_load_workflow(File.binread(absolute_path), relative_path)
      rescue Psych::SyntaxError => error
        errors << format(
          "workflow-yaml: %s:%d:%d: invalid YAML syntax (%s)",
          relative_path,
          error.line,
          error.column,
          error.problem
        )
      rescue ModelError => error
        errors << "workflow-yaml: #{error.message}"
      rescue ArgumentError => error
        errors << "workflow-yaml: #{relative_path}: malformed YAML (#{error.class})"
      rescue SystemStackError
        errors << "workflow-yaml: #{relative_path}: depth: bounded YAML validation failed"
      rescue SystemCallError
        errors << "workflow-yaml: #{relative_path}: tracked workflow is unreadable"
      end
    end

    unless errors.empty?
      errors.each { |error| warn error }
      return 1
    end

    puts "quality-gate: workflow YAML syntax verified " \
         "(#{workflows.length} tracked files); unique mapping keys verified"
    0
  end
end

# Compatibility for callers that used the original module name. Both production
# readers now execute WorkflowYamlSafety.safe_load_workflow.
WorkflowYamlValidation = WorkflowYamlSafety

if $PROGRAM_NAME == __FILE__
  if ARGV.length > 1
    warn "usage: validate-workflow-yaml.rb [repository-root]"
    exit 2
  end

  repository_root = Pathname.new(ARGV.fetch(0) do
    File.expand_path("..", __dir__)
  end).expand_path
  exit WorkflowYamlSafety.run(repository_root)
end
