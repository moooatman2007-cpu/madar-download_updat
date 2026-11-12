-- ============================================================================
-- Migration: 20261112000001_production_performance_and_security_hardening.sql
-- Purpose: 
--   1. Add covering indexes for 7 unindexed foreign keys to optimize JOINs and deletes.
--   2. Drop duplicate unique index on public.organization_members.
--   3. Optimize RLS policies by wrapping auth.uid() in (SELECT auth.uid()) to avoid
--      per-row re-evaluation overhead (InitPlan optimization).
--   4. Consolidate overlapping permissive policies on public.groups to improve evaluation speed.
-- ============================================================================

-- Step 1: Covering Indexes for Foreign Keys
CREATE INDEX IF NOT EXISTS idx_recitation_notification_events_approved_by 
ON public.recitation_notification_events(approved_by);

CREATE INDEX IF NOT EXISTS idx_secretary_audit_logs_actor_id 
ON public.secretary_audit_logs(actor_id);

CREATE INDEX IF NOT EXISTS idx_secretary_invites_accepted_member_id 
ON public.secretary_invites(accepted_member_id);

CREATE INDEX IF NOT EXISTS idx_secretary_invites_invited_by 
ON public.secretary_invites(invited_by);

CREATE INDEX IF NOT EXISTS idx_sub_payment_requests_plan_id 
ON public.subscription_payment_requests(plan_id);

CREATE INDEX IF NOT EXISTS idx_sub_payment_requests_reviewed_by 
ON public.subscription_payment_requests(reviewed_by);

CREATE INDEX IF NOT EXISTS idx_subscriptions_plan_id 
ON public.subscriptions(plan_id);

-- Step 2: Drop redundant duplicate index
DROP INDEX IF EXISTS public.organization_members_org_teacher_unique;

-- Step 3: Consolidate / Optimize RLS Policies with (SELECT auth.uid())

-- 3.1 Attendance
DROP POLICY IF EXISTS "attendance_select_own" ON public.attendance;
CREATE POLICY "attendance_select_own" ON public.attendance
FOR SELECT TO authenticated
USING (
  ((teacher_id = (SELECT auth.uid())) AND is_active_teacher()) 
  OR has_org_permission(organization_id, 'view_attendance')
);

DROP POLICY IF EXISTS "attendance_insert_own" ON public.attendance;
CREATE POLICY "attendance_insert_own" ON public.attendance
FOR INSERT TO authenticated
WITH CHECK (
  ((teacher_id = (SELECT auth.uid())) AND is_active_teacher()) 
  OR (has_org_permission(organization_id, 'record_attendance') AND (teacher_id = organization_owner_id(organization_id)))
);

DROP POLICY IF EXISTS "attendance_update_own" ON public.attendance;
CREATE POLICY "attendance_update_own" ON public.attendance
FOR UPDATE TO authenticated
USING (
  ((teacher_id = (SELECT auth.uid())) AND is_active_teacher()) 
  OR has_org_permission(organization_id, 'edit_attendance')
)
WITH CHECK (
  ((teacher_id = (SELECT auth.uid())) AND is_active_teacher()) 
  OR (has_org_permission(organization_id, 'edit_attendance') AND (teacher_id = organization_owner_id(organization_id)))
);

DROP POLICY IF EXISTS "attendance_delete_own" ON public.attendance;
CREATE POLICY "attendance_delete_own" ON public.attendance
FOR DELETE TO authenticated
USING (
  ((teacher_id = (SELECT auth.uid())) AND is_active_teacher()) 
  OR has_org_permission(organization_id, 'edit_attendance')
);

-- 3.2 Students
DROP POLICY IF EXISTS "students_select_own" ON public.students;
CREATE POLICY "students_select_own" ON public.students
FOR SELECT TO authenticated
USING (
  is_admin()
  OR ((teacher_id = (SELECT auth.uid())) AND is_active_teacher()) 
  OR has_org_permission(organization_id, 'view_students')
);
-- Clean up duplicate admin policy if it exists
DROP POLICY IF EXISTS "admins_select_all_students" ON public.students;

DROP POLICY IF EXISTS "students_insert_own" ON public.students;
CREATE POLICY "students_insert_own" ON public.students
FOR INSERT TO authenticated
WITH CHECK (
  ((teacher_id = (SELECT auth.uid())) AND is_active_teacher()) 
  OR (has_org_permission(organization_id, 'add_students') AND (teacher_id = organization_owner_id(organization_id)))
);

DROP POLICY IF EXISTS "students_update_own" ON public.students;
CREATE POLICY "students_update_own" ON public.students
FOR UPDATE TO authenticated
USING (
  ((teacher_id = (SELECT auth.uid())) AND is_active_teacher()) 
  OR has_org_permission(organization_id, 'edit_students')
)
WITH CHECK (
  ((teacher_id = (SELECT auth.uid())) AND is_active_teacher()) 
  OR (has_org_permission(organization_id, 'edit_students') AND (teacher_id = organization_owner_id(organization_id)))
);

DROP POLICY IF EXISTS "students_delete_own" ON public.students;
CREATE POLICY "students_delete_own" ON public.students
FOR DELETE TO authenticated
USING (
  ((teacher_id = (SELECT auth.uid())) AND is_active_teacher()) 
  OR has_org_permission(organization_id, 'edit_students')
);

-- 3.3 Groups (Remove duplicate groups_tenant_isolation and optimize actions)
DROP POLICY IF EXISTS "groups_tenant_isolation" ON public.groups;

DROP POLICY IF EXISTS "groups_select_own" ON public.groups;
CREATE POLICY "groups_select_own" ON public.groups
FOR SELECT TO authenticated
USING (
  ((teacher_id = (SELECT auth.uid())) AND is_active_teacher()) 
  OR has_org_permission(organization_id, 'view_students')
);

DROP POLICY IF EXISTS "groups_insert_own" ON public.groups;
CREATE POLICY "groups_insert_own" ON public.groups
FOR INSERT TO authenticated
WITH CHECK (
  ((teacher_id = (SELECT auth.uid())) AND is_active_teacher()) 
  OR (has_org_permission(organization_id, 'manage_groups') AND (teacher_id = organization_owner_id(organization_id)))
);

DROP POLICY IF EXISTS "groups_update_own" ON public.groups;
CREATE POLICY "groups_update_own" ON public.groups
FOR UPDATE TO authenticated
USING (
  ((teacher_id = (SELECT auth.uid())) AND is_active_teacher()) 
  OR has_org_permission(organization_id, 'manage_groups')
)
WITH CHECK (
  ((teacher_id = (SELECT auth.uid())) AND is_active_teacher()) 
  OR (has_org_permission(organization_id, 'manage_groups') AND (teacher_id = organization_owner_id(organization_id)))
);

DROP POLICY IF EXISTS "groups_delete_own" ON public.groups;
CREATE POLICY "groups_delete_own" ON public.groups
FOR DELETE TO authenticated
USING (
  ((teacher_id = (SELECT auth.uid())) AND is_active_teacher()) 
  OR has_org_permission(organization_id, 'manage_groups')
);

-- 3.4 Exams
DROP POLICY IF EXISTS "exams_select_own" ON public.exams;
CREATE POLICY "exams_select_own" ON public.exams
FOR SELECT TO authenticated
USING (
  ((teacher_id = (SELECT auth.uid())) AND is_active_teacher()) 
  OR has_org_permission(organization_id, 'view_grades')
);

DROP POLICY IF EXISTS "exams_insert_own" ON public.exams;
CREATE POLICY "exams_insert_own" ON public.exams
FOR INSERT TO authenticated
WITH CHECK (
  ((teacher_id = (SELECT auth.uid())) AND is_active_teacher()) 
  OR (has_org_permission(organization_id, 'record_grades') AND (teacher_id = organization_owner_id(organization_id)))
);

DROP POLICY IF EXISTS "exams_update_own" ON public.exams;
CREATE POLICY "exams_update_own" ON public.exams
FOR UPDATE TO authenticated
USING (
  ((teacher_id = (SELECT auth.uid())) AND is_active_teacher()) 
  OR has_org_permission(organization_id, 'record_grades')
)
WITH CHECK (
  ((teacher_id = (SELECT auth.uid())) AND is_active_teacher()) 
  OR (has_org_permission(organization_id, 'record_grades') AND (teacher_id = organization_owner_id(organization_id)))
);

DROP POLICY IF EXISTS "exams_delete_own" ON public.exams;
CREATE POLICY "exams_delete_own" ON public.exams
FOR DELETE TO authenticated
USING (
  ((teacher_id = (SELECT auth.uid())) AND is_active_teacher()) 
  OR has_org_permission(organization_id, 'record_grades')
);

-- 3.5 Homework
DROP POLICY IF EXISTS "homework_select_own" ON public.homework;
CREATE POLICY "homework_select_own" ON public.homework
FOR SELECT TO authenticated
USING (
  ((teacher_id = (SELECT auth.uid())) AND is_active_teacher()) 
  OR has_org_permission(organization_id, 'view_homework')
);

DROP POLICY IF EXISTS "homework_insert_own" ON public.homework;
CREATE POLICY "homework_insert_own" ON public.homework
FOR INSERT TO authenticated
WITH CHECK (
  ((teacher_id = (SELECT auth.uid())) AND is_active_teacher()) 
  OR (has_org_permission(organization_id, 'manage_homework') AND (teacher_id = organization_owner_id(organization_id)))
);

DROP POLICY IF EXISTS "homework_update_own" ON public.homework;
CREATE POLICY "homework_update_own" ON public.homework
FOR UPDATE TO authenticated
USING (
  ((teacher_id = (SELECT auth.uid())) AND is_active_teacher()) 
  OR has_org_permission(organization_id, 'manage_homework')
)
WITH CHECK (
  ((teacher_id = (SELECT auth.uid())) AND is_active_teacher()) 
  OR (has_org_permission(organization_id, 'manage_homework') AND (teacher_id = organization_owner_id(organization_id)))
);

DROP POLICY IF EXISTS "homework_delete_own" ON public.homework;
CREATE POLICY "homework_delete_own" ON public.homework
FOR DELETE TO authenticated
USING (
  ((teacher_id = (SELECT auth.uid())) AND is_active_teacher()) 
  OR has_org_permission(organization_id, 'manage_homework')
);

-- 3.6 Recitations
DROP POLICY IF EXISTS "recitations_select_own" ON public.recitations;
CREATE POLICY "recitations_select_own" ON public.recitations
FOR SELECT TO authenticated
USING (
  ((teacher_id = (SELECT auth.uid())) AND is_active_teacher()) 
  OR has_org_permission(organization_id, 'view_recitations')
);

DROP POLICY IF EXISTS "recitations_insert_own" ON public.recitations;
CREATE POLICY "recitations_insert_own" ON public.recitations
FOR INSERT TO authenticated
WITH CHECK (
  ((teacher_id = (SELECT auth.uid())) AND is_active_teacher()) 
  OR (has_org_permission(organization_id, 'record_recitations') AND (teacher_id = organization_owner_id(organization_id)))
);

DROP POLICY IF EXISTS "recitations_update_own" ON public.recitations;
CREATE POLICY "recitations_update_own" ON public.recitations
FOR UPDATE TO authenticated
USING (
  ((teacher_id = (SELECT auth.uid())) AND is_active_teacher()) 
  OR has_org_permission(organization_id, 'record_recitations')
)
WITH CHECK (
  ((teacher_id = (SELECT auth.uid())) AND is_active_teacher()) 
  OR (has_org_permission(organization_id, 'record_recitations') AND (teacher_id = organization_owner_id(organization_id)))
);

DROP POLICY IF EXISTS "recitations_delete_own" ON public.recitations;
CREATE POLICY "recitations_delete_own" ON public.recitations
FOR DELETE TO authenticated
USING (
  ((teacher_id = (SELECT auth.uid())) AND is_active_teacher()) 
  OR has_org_permission(organization_id, 'record_recitations')
);

-- 3.7 Lesson Payments
DROP POLICY IF EXISTS "lesson_payments_select_own" ON public.lesson_payments;
CREATE POLICY "lesson_payments_select_own" ON public.lesson_payments
FOR SELECT TO authenticated
USING (
  ((teacher_id = (SELECT auth.uid())) AND is_active_teacher()) 
  OR has_org_permission(organization_id, 'view_payments')
);

DROP POLICY IF EXISTS "lesson_payments_insert_own" ON public.lesson_payments;
CREATE POLICY "lesson_payments_insert_own" ON public.lesson_payments
FOR INSERT TO authenticated
WITH CHECK (
  ((teacher_id = (SELECT auth.uid())) AND is_active_teacher()) 
  OR (has_org_permission(organization_id, 'record_payments') AND (teacher_id = organization_owner_id(organization_id)))
);

DROP POLICY IF EXISTS "lesson_payments_update_own" ON public.lesson_payments;
CREATE POLICY "lesson_payments_update_own" ON public.lesson_payments
FOR UPDATE TO authenticated
USING (
  ((teacher_id = (SELECT auth.uid())) AND is_active_teacher()) 
  OR has_org_permission(organization_id, 'record_payments')
)
WITH CHECK (
  ((teacher_id = (SELECT auth.uid())) AND is_active_teacher()) 
  OR (has_org_permission(organization_id, 'record_payments') AND (teacher_id = organization_owner_id(organization_id)))
);

DROP POLICY IF EXISTS "lesson_payments_delete_own" ON public.lesson_payments;
CREATE POLICY "lesson_payments_delete_own" ON public.lesson_payments
FOR DELETE TO authenticated
USING (
  ((teacher_id = (SELECT auth.uid())) AND is_active_teacher()) 
  OR has_org_permission(organization_id, 'record_payments')
);

-- 3.8 Monthly Reports
DROP POLICY IF EXISTS "monthly_reports_select_own" ON public.monthly_reports;
CREATE POLICY "monthly_reports_select_own" ON public.monthly_reports
FOR SELECT TO authenticated
USING (
  ((teacher_id = (SELECT auth.uid())) AND is_active_teacher()) 
  OR has_org_permission(organization_id, 'view_reports')
);

DROP POLICY IF EXISTS "monthly_reports_insert_own" ON public.monthly_reports;
CREATE POLICY "monthly_reports_insert_own" ON public.monthly_reports
FOR INSERT TO authenticated
WITH CHECK (
  ((teacher_id = (SELECT auth.uid())) AND is_active_teacher()) 
  OR (has_org_permission(organization_id, 'view_reports') AND (teacher_id = organization_owner_id(organization_id)))
);

DROP POLICY IF EXISTS "monthly_reports_update_own" ON public.monthly_reports;
CREATE POLICY "monthly_reports_update_own" ON public.monthly_reports
FOR UPDATE TO authenticated
USING (
  ((teacher_id = (SELECT auth.uid())) AND is_active_teacher()) 
  OR has_org_permission(organization_id, 'view_reports')
)
WITH CHECK (
  ((teacher_id = (SELECT auth.uid())) AND is_active_teacher()) 
  OR (has_org_permission(organization_id, 'view_reports') AND (teacher_id = organization_owner_id(organization_id)))
);

DROP POLICY IF EXISTS "monthly_reports_delete_own" ON public.monthly_reports;
CREATE POLICY "monthly_reports_delete_own" ON public.monthly_reports
FOR DELETE TO authenticated
USING (
  ((teacher_id = (SELECT auth.uid())) AND is_active_teacher()) 
  OR has_org_permission(organization_id, 'view_reports')
);

-- 3.9 Notifications
DROP POLICY IF EXISTS "notifications_select_own" ON public.notifications;
CREATE POLICY "notifications_select_own" ON public.notifications
FOR SELECT TO authenticated
USING (
  ((teacher_id = (SELECT auth.uid())) AND is_active_teacher()) 
  OR has_org_permission(organization_id, 'send_whatsapp')
);

DROP POLICY IF EXISTS "notifications_insert_own" ON public.notifications;
CREATE POLICY "notifications_insert_own" ON public.notifications
FOR INSERT TO authenticated
WITH CHECK (
  ((teacher_id = (SELECT auth.uid())) AND is_active_teacher()) 
  OR (has_org_permission(organization_id, 'send_whatsapp') AND (teacher_id = organization_owner_id(organization_id)))
);

DROP POLICY IF EXISTS "notifications_update_own" ON public.notifications;
CREATE POLICY "notifications_update_own" ON public.notifications
FOR UPDATE TO authenticated
USING (
  ((teacher_id = (SELECT auth.uid())) AND is_active_teacher()) 
  OR has_org_permission(organization_id, 'send_whatsapp')
)
WITH CHECK (
  ((teacher_id = (SELECT auth.uid())) AND is_active_teacher()) 
  OR (has_org_permission(organization_id, 'send_whatsapp') AND (teacher_id = organization_owner_id(organization_id)))
);

DROP POLICY IF EXISTS "notifications_delete_own" ON public.notifications;
CREATE POLICY "notifications_delete_own" ON public.notifications
FOR DELETE TO authenticated
USING (
  ((teacher_id = (SELECT auth.uid())) AND is_active_teacher()) 
  OR has_org_permission(organization_id, 'send_whatsapp')
);

