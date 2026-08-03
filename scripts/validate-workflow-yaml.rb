#!/usr/bin/env ruby

require "open3"
require "pathname"
require "psych"

if ARGV.length > 1
  warn "usage: validate-workflow-yaml.rb [repository-root]"
  exit 2
end

repository_root = Pathname.new(ARGV.fetch(0) do
  File.expand_path("..", __dir__)
end).expand_path

unless repository_root.directory?
  warn "workflow-yaml: repository root is not a directory"
  exit 1
end

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
  warn "workflow-yaml: unable to inventory tracked workflow files"
  exit 1
end

workflow_path = %r{\A\.github/workflows/[^/]+\.(?:yml|yaml)\z}
workflows = inventory.split("\0").select { |path| workflow_path.match?(path) }.sort
if workflows.empty?
  warn "workflow-yaml: no tracked workflow files found"
  exit 1
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
    Psych.parse_stream(File.binread(absolute_path), filename: relative_path)
  rescue Psych::SyntaxError => error
    errors << format(
      "workflow-yaml: %s:%d:%d: invalid YAML syntax (%s)",
      relative_path,
      error.line,
      error.column,
      error.problem
    )
  rescue SystemCallError
    errors << "workflow-yaml: #{relative_path}: tracked workflow is unreadable"
  end
end

unless errors.empty?
  errors.each { |error| warn error }
  exit 1
end

puts "quality-gate: workflow YAML syntax verified (#{workflows.length} tracked files)"
