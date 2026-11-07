import { execFileSync } from 'node:child_process'
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

// 公開先（AWS）の DB（RDS）に psql を流す（基本設計書 2.4。npm run perf の PERF_DB=aws）。
// RDS は VPC の中からしかつなげないので、psql の入った PostgreSQL のイメージを、API と同じセキュリティグループ（SG-2）の
// Fargate のタスクとして 1 回だけ動かす。パスワードは API と同じく Parameter Store から渡し、出力は CloudWatch Logs から読む。
// 名前は deploy/terraform のものと合わせる。AWS CLI（~/.aws の鍵）を使う

const REGION = process.env.AWS_REGION ?? 'ap-northeast-1'
const IMAGE = 'public.ecr.aws/docker/library/postgres:17-alpine'
const FAMILY = 'bl-perf-psql'
const LOG_GROUP = '/bl/app'

function aws<T>(args: string[]): T {
  const out = execFileSync('aws', [...args, '--region', REGION, '--output', 'json'], { encoding: 'utf8' })
  return (out.trim() ? JSON.parse(out) : null) as T
}

export function accountId(): string {
  return aws<{ Account: string }>(['sts', 'get-caller-identity']).Account
}

// 公開先の環境の名前から、タスクを動かすのに要るものを探す
function environment() {
  const db = aws<{ DBInstances: { DBInstanceStatus: string, Endpoint: { Address: string } }[] }>(
    ['rds', 'describe-db-instances', '--db-instance-identifier', 'bl-db']).DBInstances[0]
  if (db.DBInstanceStatus !== 'available') {
    throw new Error(`RDS（bl-db）が ${db.DBInstanceStatus} です。bash deploy/start.sh で起動してから実行してください`)
  }
  const subnets = aws<{ Subnets: { SubnetId: string }[] }>(
    ['ec2', 'describe-subnets', '--filters', 'Name=tag:Name,Values=bl-public-a,bl-public-c']).Subnets.map((s) => s.SubnetId)
  const group = aws<{ SecurityGroups: { GroupId: string }[] }>(
    ['ec2', 'describe-security-groups', '--filters', 'Name=group-name,Values=bl-app']).SecurityGroups[0].GroupId
  const role = aws<{ Role: { Arn: string } }>(['iam', 'get-role', '--role-name', 'bl-task-execution']).Role.Arn
  const password = aws<{ Parameter: { ARN: string } }>(['ssm', 'get-parameter', '--name', '/bl/db/password']).Parameter.ARN
  return { host: db.Endpoint.Address, subnets, group, role, password }
}

// psql を流して、出力を返す。input（SQL）があれば -f で読ませる。失敗したら psql の出力つきで例外にする
export function remotePsql(database: string, args: string[], input = ''): string {
  const env = environment()
  const script = input
    ? 'echo "$SQL" | base64 -d > /tmp/in.sql && exec psql -v ON_ERROR_STOP=1 -q "$@" -f /tmp/in.sql'
    : 'exec psql -v ON_ERROR_STOP=1 -q "$@"'
  const container = {
    name: 'psql',
    image: IMAGE,
    essential: true,
    command: ['sh', '-c', script, 'psql', ...args],
    environment: [
      { name: 'PGHOST', value: env.host },
      { name: 'PGDATABASE', value: database },
      { name: 'PGUSER', value: 'booklog' },
      { name: 'PGSSLMODE', value: 'require' },
      { name: 'SQL', value: Buffer.from(input).toString('base64') },
    ],
    secrets: [{ name: 'PGPASSWORD', valueFrom: env.password }],
    logConfiguration: {
      logDriver: 'awslogs',
      options: { 'awslogs-group': LOG_GROUP, 'awslogs-region': REGION, 'awslogs-stream-prefix': 'perf' },
    },
  }
  const dir = mkdtempSync(join(tmpdir(), 'bl-perf-'))
  const file = join(dir, 'containers.json')
  writeFileSync(file, JSON.stringify([container]))
  let taskDefinition: string
  try {
    taskDefinition = aws<{ taskDefinition: { taskDefinitionArn: string } }>(['ecs', 'register-task-definition',
      '--family', FAMILY, '--requires-compatibilities', 'FARGATE', '--network-mode', 'awsvpc', '--cpu', '256',
      '--memory', '512', '--runtime-platform', 'cpuArchitecture=ARM64,operatingSystemFamily=LINUX',
      '--execution-role-arn', env.role, '--container-definitions', `file://${file}`]).taskDefinition.taskDefinitionArn
  } finally {
    rmSync(dir, { recursive: true, force: true })
  }

  try {
    const network = { awsvpcConfiguration: { subnets: env.subnets, securityGroups: [env.group], assignPublicIp: 'ENABLED' } }
    const run = aws<{ tasks: { taskArn: string }[], failures: unknown[] }>(['ecs', 'run-task', '--cluster', 'bl-cluster',
      '--launch-type', 'FARGATE', '--task-definition', taskDefinition, '--network-configuration', JSON.stringify(network)])
    if (!run.tasks.length) throw new Error(`タスクを起動できませんでした：${JSON.stringify(run.failures)}`)
    const taskArn = run.tasks[0].taskArn
    console.log('公開先の DB に psql を流しています（Fargate のタスクの起動を含めて 1〜2 分）')
    aws(['ecs', 'wait', 'tasks-stopped', '--cluster', 'bl-cluster', '--tasks', taskArn])

    const task = aws<{ tasks: { stoppedReason?: string, containers: { exitCode?: number, reason?: string }[] }[] }>(
      ['ecs', 'describe-tasks', '--cluster', 'bl-cluster', '--tasks', taskArn]).tasks[0]
    const stream = `perf/psql/${taskArn.split('/').pop()}`
    let output = ''
    try {
      const logs = aws<{ events: { message: string }[] }>(['logs', 'get-log-events', '--log-group-name', LOG_GROUP,
        '--log-stream-name', stream, '--start-from-head'])
      output = logs.events.map((e) => e.message).join('\n')
    } catch {
      // ログが届く前に止まった（イメージを取れなかったなど）
    }
    const exitCode = task.containers[0]?.exitCode
    if (exitCode !== 0) {
      throw new Error(`psql が失敗しました（終了コード ${exitCode ?? 'なし'}。${task.stoppedReason ?? ''} ${task.containers[0]?.reason ?? ''}）\n${output}`)
    }
    return output ? `${output}\n` : ''
  } finally {
    aws(['ecs', 'deregister-task-definition', '--task-definition', taskDefinition])
  }
}
